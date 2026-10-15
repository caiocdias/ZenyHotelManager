package com.zeny.hotel.hotel.application

import com.zeny.hotel.hotel.BusinessDateAdvanced
import com.zeny.hotel.hotel.HotelApi
import com.zeny.hotel.hotel.OperationalSettings
import com.zeny.hotel.hotel.PolicySettings
import com.zeny.hotel.hotel.TaxSettings
import com.zeny.hotel.hotel.domain.BusinessDay
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.hotel.domain.HotelOperations
import com.zeny.hotel.hotel.domain.HotelPolicy
import com.zeny.hotel.hotel.domain.HotelTaxes
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A API pública do `hotel` ([HotelApi]) para os outros módulos: leituras sem checar permissão e o
 * avanço da data operacional, que trava a linha da data (`SELECT … FOR UPDATE`), avança exatamente
 * um dia a partir da data esperada e publica [BusinessDateAdvanced] pelo [ApplicationEventPublisher]
 * dentro da transação: o Event Publication Registry (outbox) o grava no mesmo commit, e rollback
 * não deixa nem a data nova nem o evento.
 */
@Service
class HotelApiService(
    private val store: HotelStore,
    private val events: ApplicationEventPublisher,
    private val audit: AuditContext,
    private val clock: Clock,
) : HotelApi {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun timezone(): ZoneId = (store.hotel() ?: notInitialized()).timezone

    @Transactional(readOnly = true)
    override fun currentBusinessDate(): LocalDate = (store.businessDay() ?: notInitialized()).current

    @Transactional(readOnly = true)
    override fun policy(): PolicySettings = (store.policy() ?: notInitialized()).settings()

    @Transactional(readOnly = true)
    override fun taxSettings(): TaxSettings = (store.taxes() ?: notInitialized()).settings()

    @Transactional(readOnly = true)
    override fun operationalSettings(): OperationalSettings = (store.operations() ?: notInitialized()).settings()

    @Audited(HotelAudit.ADVANCE_BUSINESS_DATE)
    @Transactional
    override fun advanceBusinessDate(expectedCurrent: LocalDate): BusinessDateAdvanced {
        val day = store.businessDayForUpdate() ?: notInitialized()
        audit.track(HotelAudit.BUSINESS_DAY, day.hotelId.value) { day.auditState() }
        val event = day.advance(expectedCurrent, clock.instant())
        store.flush()
        events.publishEvent(event)
        log.info("Data operacional avançada de {} para {}", event.previous, event.current)
        return event
    }
}

/**
 * Cria o hotel no primeiro boot (RF-CFG-01 a 05): ainda sem dados cadastrais, com o fuso padrão
 * (RNF-14), as políticas, taxas e parâmetros padrão e a data operacional inicial, que é a data de
 * hoje no fuso do hotel. Se o hotel já existe, não faz nada.
 */
@Service
class HotelInitializer(
    private val store: HotelStore,
    private val audit: AuditContext,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Devolve `true` se criou o hotel. */
    @Audited(HotelAudit.INITIALIZE)
    @Transactional
    fun ensureInitialized(): Boolean {
        if (store.hotel() != null) {
            audit.noChanges()
            return false
        }
        val now = clock.instant()
        val hotel = Hotel.create(Hotel.DEFAULT_TIMEZONE, now)
        val policy = HotelPolicy.create(hotel.id, now)
        val taxes = HotelTaxes.create(hotel.id, now)
        val operations = HotelOperations.create(hotel.id, now)
        val businessDay = BusinessDay.start(hotel.id, LocalDate.now(clock.withZone(hotel.timezone)), now)
        store.add(hotel, policy, taxes, operations, businessDay)
        val id = hotel.id.value
        audit.created(HotelAudit.HOTEL, id) { hotel.auditState() }
        audit.created(HotelAudit.POLICY, id) { policy.settings().auditState() }
        audit.created(HotelAudit.TAX_SETTINGS, id) { taxes.settings().auditState() }
        audit.created(HotelAudit.OPERATIONAL_SETTINGS, id) { operations.settings().auditState() }
        audit.created(HotelAudit.BUSINESS_DAY, id) { businessDay.auditState() }
        log.info("Hotel criado com a data operacional {} no fuso {}", businessDay.current, hotel.timezone)
        return true
    }
}
