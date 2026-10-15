package com.zeny.hotel.hotel.infrastructure

import com.zeny.hotel.hotel.application.HotelInitializer
import com.zeny.hotel.hotel.application.HotelStore
import com.zeny.hotel.hotel.domain.BusinessDay
import com.zeny.hotel.hotel.domain.Hotel
import com.zeny.hotel.hotel.domain.HotelOperations
import com.zeny.hotel.hotel.domain.HotelPolicy
import com.zeny.hotel.hotel.domain.HotelTaxes
import jakarta.persistence.LockModeType
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import java.util.UUID

interface HotelJpaRepository : JpaRepository<Hotel, UUID>

interface HotelPolicyJpaRepository : JpaRepository<HotelPolicy, UUID>

interface HotelTaxesJpaRepository : JpaRepository<HotelTaxes, UUID>

interface HotelOperationsJpaRepository : JpaRepository<HotelOperations, UUID>

interface BusinessDayJpaRepository : JpaRepository<BusinessDay, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM BusinessDay d")
    fun findAllForUpdate(): List<BusinessDay>
}

/** As partes da configuração do hotel por JPA, na transação corrente; cada tabela tem uma linha só. */
@Component
@Suppress("LongParameterList") // um repositório por parte da configuração
class JpaHotelStore(
    private val hotels: HotelJpaRepository,
    private val policies: HotelPolicyJpaRepository,
    private val taxes: HotelTaxesJpaRepository,
    private val operations: HotelOperationsJpaRepository,
    private val businessDays: BusinessDayJpaRepository,
) : HotelStore {
    override fun hotel(): Hotel? = hotels.findAll().singleOrNull()

    override fun policy(): HotelPolicy? = policies.findAll().singleOrNull()

    override fun taxes(): HotelTaxes? = taxes.findAll().singleOrNull()

    override fun operations(): HotelOperations? = operations.findAll().singleOrNull()

    override fun businessDay(): BusinessDay? = businessDays.findAll().singleOrNull()

    override fun businessDayForUpdate(): BusinessDay? = businessDays.findAllForUpdate().singleOrNull()

    override fun add(
        hotel: Hotel,
        policy: HotelPolicy,
        taxes: HotelTaxes,
        operations: HotelOperations,
        businessDay: BusinessDay,
    ) {
        hotels.saveAndFlush(hotel)
        policies.save(policy)
        this.taxes.save(taxes)
        this.operations.save(operations)
        businessDays.save(businessDay)
        businessDays.flush()
    }

    override fun flush() = hotels.flush()
}

/** No boot, cria o hotel se ainda não existe ([HotelInitializer]). Falha impede a partida. */
@Component
class HotelStartupRunner(
    private val initializer: HotelInitializer,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        initializer.ensureInitialized()
    }
}
