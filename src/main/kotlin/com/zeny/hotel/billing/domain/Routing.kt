package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.RoutingRuleId
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.TypedIdConverter
import com.zeny.hotel.shared.UserId
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Período em datas de negócio, inclusive nas duas pontas; [endsOn] nulo: sem fim. */
data class BusinessPeriod(
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
) {
    init {
        require(endsOn == null || !endsOn.isBefore(startsOn)) { "Período com o fim antes do início" }
    }

    fun contains(date: LocalDate): Boolean = !date.isBefore(startsOn) && (endsOn == null || !date.isAfter(endsOn))

    /** Os dias em comum; `null` se não há nenhum. */
    fun intersect(other: BusinessPeriod): BusinessPeriod? {
        val start = maxOf(startsOn, other.startsOn)
        val end =
            when {
                endsOn == null -> other.endsOn
                other.endsOn == null -> endsOn
                else -> minOf(endsOn, other.endsOn)
            }
        return if (end != null && end.isBefore(start)) null else BusinessPeriod(start, end)
    }

    companion object {
        /** O período de uma regra, com a recusa no campo `endsOn` se o fim vem antes do início. */
        fun of(
            startsOn: LocalDate,
            endsOn: LocalDate?,
        ): BusinessPeriod {
            if (endsOn != null && endsOn.isBefore(startsOn)) {
                throw BillingRules.invalid(
                    BillingErrorCodes.ROUTING_RULE_INVALID,
                    "endsOn",
                    "O fim do período não pode vir antes do início.",
                )
            }
            return BusinessPeriod(startsOn, endsOn)
        }
    }
}

/**
 * O que uma regra de roteamento cobre (RF-CTA-04): um conjunto de códigos de lançamento ([Codes], o nível mais
 * específico) ou um grupo de receita ([Group]). Na mesma conta, a regra por código prevalece sobre a por grupo
 * (decisão do usuário em 2026-10-19).
 */
sealed interface RoutingCriteria {
    /** Se a regra cobre um lançamento no código [codeId], do grupo [group]. */
    fun matches(
        codeId: TransactionCodeId,
        group: RevenueGroup,
    ): Boolean

    /**
     * Os lançamentos cobertos pelas duas: `null` se nenhum. [groupOf] dá o grupo de receita de um código (fixo desde
     * a criação dele); código desconhecido não casa com grupo nenhum.
     */
    fun intersect(
        other: RoutingCriteria,
        groupOf: (TransactionCodeId) -> RevenueGroup?,
    ): RoutingCriteria?

    data class Codes(
        val ids: Set<TransactionCodeId>,
    ) : RoutingCriteria {
        init {
            require(ids.isNotEmpty()) { "Regra por código sem códigos" }
        }

        override fun matches(
            codeId: TransactionCodeId,
            group: RevenueGroup,
        ): Boolean = codeId in ids

        override fun intersect(
            other: RoutingCriteria,
            groupOf: (TransactionCodeId) -> RevenueGroup?,
        ): RoutingCriteria? {
            val common =
                when (other) {
                    is Codes -> ids intersect other.ids
                    is Group -> ids.filterTo(mutableSetOf()) { groupOf(it) == other.group }
                }
            return common.takeIf { it.isNotEmpty() }?.let(::Codes)
        }
    }

    data class Group(
        val group: RevenueGroup,
    ) : RoutingCriteria {
        override fun matches(
            codeId: TransactionCodeId,
            group: RevenueGroup,
        ): Boolean = group == this.group

        override fun intersect(
            other: RoutingCriteria,
            groupOf: (TransactionCodeId) -> RevenueGroup?,
        ): RoutingCriteria? =
            when (other) {
                is Codes -> other.intersect(this, groupOf)
                is Group -> takeIf { other.group == group }
            }
    }
}

/**
 * Regra de roteamento (RF-CTA-04, SPEC §8.2.7): os lançamentos pedidos na conta de origem ([sourceFolioId], fixa)
 * cujo código está em [criteria], na data operacional dentro de [period], vão para a conta de destino
 * ([targetFolioId]) no momento do lançamento. Não muda lançamentos já feitos. Nunca é apagada (o banco recusa), só
 * desativada; as regras de sobreposição, ciclo e destino aberto são do [RoutingNetwork] e do caso de uso.
 */
@Entity
@Table(name = "routing_rule", schema = "billing")
@Suppress("LongParameterList") // os dados fixos e os editáveis da criação
class RoutingRule private constructor(
    @Id
    val id: RoutingRuleId,
    @Column(name = "source_folio_id", nullable = false, updatable = false)
    private val sourceFolioValue: UUID,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "created_by", updatable = false)
    private val createdByValue: UUID?,
    @Column(name = "target_folio_id", nullable = false)
    private var targetFolioValue: UUID,
    @Column(name = "starts_on", nullable = false)
    private var startsOnValue: LocalDate,
    @Column(name = "ends_on")
    private var endsOnValue: LocalDate?,
) {
    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_group")
    private var revenueGroupValue: RevenueGroup? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "routing_rule_code",
        schema = "billing",
        joinColumns = [JoinColumn(name = "routing_rule_id")],
    )
    @Column(name = "transaction_code_id", nullable = false)
    @Convert(converter = TransactionCodeIdConverter::class)
    private val codeIdSet: MutableSet<TransactionCodeId> = mutableSetOf()

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    val sourceFolioId: FolioId get() = FolioId(sourceFolioValue)

    val targetFolioId: FolioId get() = FolioId(targetFolioValue)

    val createdBy: UserId? get() = createdByValue?.let(::UserId)

    val period: BusinessPeriod get() = BusinessPeriod(startsOnValue, endsOnValue)

    val criteria: RoutingCriteria
        get() = revenueGroupValue?.let(RoutingCriteria::Group) ?: RoutingCriteria.Codes(codeIdSet.toSet())

    /** Se a regra está ativa e desvia um lançamento no código [codeId] (do grupo [group]) na data [date]. */
    fun appliesTo(
        codeId: TransactionCodeId,
        group: RevenueGroup,
        date: LocalDate,
    ): Boolean = active && period.contains(date) && criteria.matches(codeId, group)

    /** Troca destino, critério e período; devolve os campos que mudaram. A origem não muda. */
    fun update(
        targetFolioId: FolioId,
        criteria: RoutingCriteria,
        period: BusinessPeriod,
        now: Instant,
    ): List<String> {
        val changed =
            buildList {
                if (targetFolioId != this@RoutingRule.targetFolioId) add("targetFolioId")
                if (criteria != this@RoutingRule.criteria) add("criteria")
                if (period != this@RoutingRule.period) add("period")
            }
        targetFolioValue = targetFolioId.value
        applyCriteria(criteria)
        startsOnValue = period.startsOn
        endsOnValue = period.endsOn
        if (changed.isNotEmpty()) updatedAt = now
        return changed
    }

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa; devolve se mudou. As condições (destino aberto, sem sobreposição nem ciclo) são do caso de uso. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    private fun applyCriteria(criteria: RoutingCriteria) {
        when (criteria) {
            is RoutingCriteria.Codes -> {
                revenueGroupValue = null
                codeIdSet.retainAll(criteria.ids)
                codeIdSet.addAll(criteria.ids)
            }

            is RoutingCriteria.Group -> {
                revenueGroupValue = criteria.group
                codeIdSet.clear()
            }
        }
    }

    override fun toString(): String = "RoutingRule(id=$id, source=$sourceFolioValue, target=$targetFolioValue)"

    companion object {
        /** Teto de códigos numa regra por código. */
        const val MAX_CODES = 50

        @Suppress("LongParameterList") // os dados da regra
        fun create(
            sourceFolioId: FolioId,
            targetFolioId: FolioId,
            criteria: RoutingCriteria,
            period: BusinessPeriod,
            createdBy: UserId?,
            now: Instant,
        ): RoutingRule =
            RoutingRule(
                RoutingRuleId.new(),
                sourceFolioId.value,
                now,
                createdBy?.value,
                targetFolioId.value,
                period.startsOn,
                period.endsOn,
            ).also { it.applyCriteria(criteria) }
    }
}

/** Conversor do [TransactionCodeId] para a coleção de códigos da [RoutingRule]. */
class TransactionCodeIdConverter : TypedIdConverter<TransactionCodeId>(::TransactionCodeId)

/**
 * As regras de roteamento ativas e as regras entre elas (RF-CTA-04; decisões do usuário em 2026-10-19):
 *
 * - **Precedência:** na conta, a regra por código que cobre o lançamento prevalece sobre a por grupo de receita.
 * - **Sobreposição:** duas regras ativas da mesma conta de origem e do mesmo nível que cobrem o mesmo código (ou o
 *   mesmo grupo) em períodos que se cruzam são recusadas (`409 ROUTING_RULE_OVERLAP`); assim cada conta tem no
 *   máximo uma regra aplicável por nível para um lançamento.
 * - **Cadeia:** com A → B e B → C vigentes, o lançamento pedido em A segue até a conta sem regra aplicável (C).
 * - **Ciclo:** uma regra que, por algum código e algum dia em comum com as regras do caminho, levaria a cadeia de
 *   volta à conta de origem é recusada (`422 ROUTING_RULE_CYCLE`), inclusive origem = destino. A conferência ignora
 *   a precedência no meio do caminho (uma regra por grupo que uma por código encobre ainda conta): pode recusar uma
 *   regra que nunca formaria ciclo de fato, nunca aceitar uma que forme.
 *
 * [groupOf] dá o grupo de receita de cada código (fixo desde a criação dele).
 */
class RoutingNetwork(
    rules: Collection<RoutingRule>,
    private val groupOf: (TransactionCodeId) -> RevenueGroup?,
) {
    private val bySource: Map<FolioId, List<RoutingRule>> =
        rules
            .filter { it.active }
            .sortedBy { it.id.value.toString() }
            .groupBy { it.sourceFolioId }

    /** A regra que desvia, na conta [folioId], um lançamento no código [codeId] na data [date]; `null` se nenhuma. */
    fun applicable(
        folioId: FolioId,
        codeId: TransactionCodeId,
        group: RevenueGroup,
        date: LocalDate,
    ): RoutingRule? {
        val candidates = bySource[folioId].orEmpty().filter { it.appliesTo(codeId, group, date) }
        return candidates.firstOrNull { it.criteria is RoutingCriteria.Codes } ?: candidates.firstOrNull()
    }

    /**
     * As regras aplicadas em sequência a um lançamento pedido em [origin] (vazio: fica nela); a conta final é o
     * destino da última. Uma cadeia que voltasse a uma conta já visitada é um estado que a recusa de ciclos impede.
     */
    fun route(
        origin: FolioId,
        codeId: TransactionCodeId,
        group: RevenueGroup,
        date: LocalDate,
    ): List<RoutingRule> {
        val path = mutableListOf<RoutingRule>()
        val visited = mutableSetOf(origin)
        var current = origin
        while (true) {
            val rule = applicable(current, codeId, group, date) ?: return path
            check(visited.add(rule.targetFolioId)) { "Roteamento em ciclo a partir da conta $origin" }
            path += rule
            current = rule.targetFolioId
        }
    }

    /** Recusa [candidate] se outra regra ativa da mesma origem e do mesmo nível cobre o mesmo em período cruzado. */
    fun requireNoOverlap(candidate: RoutingRule) {
        val criteria = candidate.criteria
        val overlapping =
            bySource[candidate.sourceFolioId].orEmpty().any { other ->
                other.id != candidate.id &&
                    other.period.intersect(candidate.period) != null &&
                    sameLevelOverlap(criteria, other.criteria)
            }
        if (overlapping) {
            throw ConflictException(
                BillingErrorCodes.ROUTING_RULE_OVERLAP,
                "Outra regra ativa desta conta já cobre os mesmos lançamentos num período que se cruza com este.",
            )
        }
    }

    /** Recusa [candidate] se ela formaria um ciclo com as regras ativas (ou se a origem é o destino). */
    fun requireNoCycle(candidate: RoutingRule) {
        val source = candidate.sourceFolioId
        if (candidate.targetFolioId == source ||
            reaches(candidate.id, source, candidate.targetFolioId, candidate.criteria, candidate.period, setOf(source))
        ) {
            throw BusinessRuleException(
                BillingErrorCodes.ROUTING_RULE_CYCLE,
                "A regra formaria um ciclo de roteamento: a conta de destino já leva estes lançamentos de volta à " +
                    "origem.",
            )
        }
    }

    /** Se, a partir de [folio], as regras (fora [skip]) levam algum lançamento de [criteria] em [period] a [source]. */
    @Suppress("LongParameterList") // o estado da busca em profundidade
    private fun reaches(
        skip: RoutingRuleId,
        source: FolioId,
        folio: FolioId,
        criteria: RoutingCriteria,
        period: BusinessPeriod,
        path: Set<FolioId>,
    ): Boolean =
        bySource[folio].orEmpty().any { rule ->
            if (rule.id == skip) return@any false
            val common = criteria.intersect(rule.criteria, groupOf) ?: return@any false
            val days = period.intersect(rule.period) ?: return@any false
            val next = rule.targetFolioId
            next == source || (next !in path && reaches(skip, source, next, common, days, path + folio))
        }

    private fun sameLevelOverlap(
        a: RoutingCriteria,
        b: RoutingCriteria,
    ): Boolean =
        when {
            a is RoutingCriteria.Codes && b is RoutingCriteria.Codes -> a.ids.any { it in b.ids }
            a is RoutingCriteria.Group && b is RoutingCriteria.Group -> a.group == b.group
            else -> false
        }
}
