package com.zeny.hotel.rates.application

import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rates.RatesPermissions
import com.zeny.hotel.rates.SeasonId
import com.zeny.hotel.rates.domain.PriceSelection
import com.zeny.hotel.rates.domain.RatesRules
import com.zeny.hotel.rates.domain.Season
import com.zeny.hotel.rates.domain.SeasonDetails
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.requireCurrentVersion
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Temporadas nomeadas (RF-TAR-04): atalhos de período para a edição em massa de preços. Consultar exige
 * `rates.plans.read`; escrever, `rates.prices.manage` (não sensível: a temporada não guarda preço). Edições com
 * `If-Match`; nunca apagadas, só desativadas.
 */
@Service
class SeasonService(
    private val store: RatesStore,
    private val directory: RatesDirectory,
    private val authorization: Authorization,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun list(
        active: Boolean?,
        page: PageParams,
    ): CursorPage<SeasonView> {
        authorization.require(RatesPermissions.PLANS_READ)
        val after = page.decodedCursor()?.let { it.string(0) to it.uuid(1) }
        val rows = directory.seasons(active, after, page.limit + 1)
        val keys = CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.first, it.second.toString()) }
        return CursorPage(store.seasons(keys.items.map { it.second }).map { it.view() }, keys.nextCursor)
    }

    @Transactional(readOnly = true)
    fun get(id: SeasonId): SeasonView {
        authorization.require(RatesPermissions.PLANS_READ)
        return find(id).view()
    }

    @Audited(RatesAudit.CREATE_SEASON)
    @Transactional
    fun create(command: SeasonCommand): SeasonView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val season = Season.create(command.details, clock.instant())
        audit.created(RatesAudit.SEASON, season.id.value) { season.auditState() }
        if (!store.tryAdd(season)) throw nameInUse()
        return season.view()
    }

    @Audited(RatesAudit.UPDATE_SEASON)
    @Transactional
    fun update(
        id: SeasonId,
        expectedVersion: Long,
        command: SeasonCommand,
    ): SeasonView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val season = find(id)
        requireCurrentVersion(expectedVersion, checkNotNull(season.version))
        audit.track(RatesAudit.SEASON, id.value) { season.auditState() }
        if (season.update(command.details, clock.instant()) && !store.tryFlush(RatesConstraints.SEASON_NAME)) {
            throw nameInUse()
        }
        return season.view()
    }

    @Audited(RatesAudit.DEACTIVATE_SEASON)
    @Transactional
    fun deactivate(id: SeasonId): SeasonView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val season = find(id)
        audit.track(RatesAudit.SEASON, id.value) { season.auditState() }
        if (season.deactivate(clock.instant())) store.flush()
        return season.view()
    }

    @Audited(RatesAudit.REACTIVATE_SEASON)
    @Transactional
    fun reactivate(id: SeasonId): SeasonView {
        authorization.require(RatesPermissions.PRICES_MANAGE)
        val season = find(id)
        audit.track(RatesAudit.SEASON, id.value) { season.auditState() }
        if (season.reactivate(clock.instant())) store.flush()
        return season.view()
    }

    private fun find(id: SeasonId) =
        store.season(id) ?: throw NotFoundException(RatesErrorCodes.SEASON_NOT_FOUND, "Temporada não encontrada.")

    private fun nameInUse() =
        ConflictException(
            RatesErrorCodes.SEASON_NAME_ALREADY_IN_USE,
            "Já existe uma temporada com este nome; se estiver desativada, reative-a.",
        )

    private val SeasonCommand.details get() = SeasonDetails(name, startsOn, endsOn)
}

/**
 * A seleção de datas de uma edição em massa (RF-TAR-04): o período explícito ou a temporada (um dos dois, `400
 * RATE_PRICE_SELECTION_INVALID`), com os dias da semana. A edição nunca começa antes da data operacional: o período
 * explícito que começa antes é recusado (`422 RATE_PRICE_PERIOD_IN_PAST`); a temporada é cortada nela (e a que já
 * terminou é recusada do mesmo modo).
 */
@Component
class PriceSelections(
    private val store: RatesStore,
) {
    /** O formato da seleção (antes de ler o plano): um dos dois modos e, no explícito, o período válido. */
    fun requireWellFormed(target: PriceTarget) {
        val from = target.from
        val to = target.to
        val explicit = from != null && to != null
        val partial = (from == null) != (to == null)
        if (partial || explicit == (target.seasonId != null)) {
            throw RatesRules.invalid(
                RatesErrorCodes.RATE_PRICE_SELECTION_INVALID,
                "seasonId",
                "Informe o período (início e fim) ou a temporada, não os dois.",
            )
        }
        if (explicit) PriceSelection.of(checkNotNull(from), checkNotNull(to), target.weekdays)
    }

    /** As datas da seleção a partir de [businessDate]. */
    fun resolve(
        target: PriceTarget,
        businessDate: LocalDate,
    ): PriceSelection {
        val seasonId = target.seasonId
        if (seasonId == null) {
            val selection = PriceSelection.of(checkNotNull(target.from), checkNotNull(target.to), target.weekdays)
            if (selection.from.isBefore(businessDate)) throw inPast()
            return selection
        }
        return activeSeason(seasonId).selection(target.weekdays).startingAt(businessDate) ?: throw inPast()
    }

    /** A temporada citada: inexistente `400 UNKNOWN_SEASON` (campo `seasonId`); desativada `422 SEASON_INACTIVE`. */
    private fun activeSeason(id: SeasonId): Season {
        val season =
            store.season(id)
                ?: throw RatesRules.invalid(
                    RatesErrorCodes.UNKNOWN_SEASON,
                    "seasonId",
                    "A temporada informada não existe.",
                )
        if (!season.active) {
            throw BusinessRuleException(RatesErrorCodes.SEASON_INACTIVE, "A temporada informada está desativada.")
        }
        return season
    }

    private fun inPast() =
        BusinessRuleException(
            RatesErrorCodes.RATE_PRICE_PERIOD_IN_PAST,
            "Os preços só são editados a partir da data operacional.",
        )
}
