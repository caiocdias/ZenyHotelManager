package com.zeny.hotel.rates.infrastructure

import com.zeny.hotel.rates.CancellationPolicyId
import com.zeny.hotel.rates.GuaranteePolicyId
import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RatePlanId
import com.zeny.hotel.rates.application.RatesConstraints
import com.zeny.hotel.rates.application.RatesStore
import com.zeny.hotel.rates.domain.CancellationPolicy
import com.zeny.hotel.rates.domain.GuaranteePolicy
import com.zeny.hotel.rates.domain.RatePackage
import com.zeny.hotel.rates.domain.RatePlan
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.util.UUID

interface RatePlanJpaRepository : JpaRepository<RatePlan, UUID>

interface CancellationPolicyJpaRepository : JpaRepository<CancellationPolicy, UUID>

interface GuaranteePolicyJpaRepository : JpaRepository<GuaranteePolicy, UUID>

interface RatePackageJpaRepository : JpaRepository<RatePackage, UUID>

/** Os agregados do `rates` por JPA, na transação corrente; as conferências de uso por `JdbcClient`, só no schema. */
@Component
@Suppress("TooManyFunctions") // um repositório e os acessos de cada agregado
class JpaRatesStore(
    private val entityManager: EntityManager,
    private val jdbc: JdbcClient,
    private val plans: RatePlanJpaRepository,
    private val cancellationPolicies: CancellationPolicyJpaRepository,
    private val guaranteePolicies: GuaranteePolicyJpaRepository,
    private val packages: RatePackageJpaRepository,
) : RatesStore {
    override fun cancellationPolicy(id: CancellationPolicyId): CancellationPolicy? =
        cancellationPolicies.findById(id.value).orElse(null)

    override fun cancellationPolicyForUpdate(id: CancellationPolicyId): CancellationPolicy? =
        forUpdate(CancellationPolicy::class.java, id.value)

    override fun cancellationPolicies(ids: List<UUID>): List<CancellationPolicy> =
        inOrder(ids, cancellationPolicies.findAllById(ids)) { it.id.value }

    override fun tryAdd(policy: CancellationPolicy): Boolean =
        unlessViolated(RatesConstraints.CANCELLATION_POLICY_NAME) { cancellationPolicies.saveAndFlush(policy) }

    override fun cancellationPolicyInUse(id: CancellationPolicyId): Boolean =
        exists("SELECT 1 FROM rates.rate_plan WHERE active AND cancellation_policy_id = :id", id.value)

    override fun guaranteePolicy(id: GuaranteePolicyId): GuaranteePolicy? =
        guaranteePolicies.findById(id.value).orElse(null)

    override fun guaranteePolicyForUpdate(id: GuaranteePolicyId): GuaranteePolicy? =
        forUpdate(GuaranteePolicy::class.java, id.value)

    override fun guaranteePolicies(ids: List<UUID>): List<GuaranteePolicy> =
        inOrder(ids, guaranteePolicies.findAllById(ids)) { it.id.value }

    override fun tryAdd(policy: GuaranteePolicy): Boolean =
        unlessViolated(RatesConstraints.GUARANTEE_POLICY_NAME) { guaranteePolicies.saveAndFlush(policy) }

    override fun guaranteePolicyInUse(id: GuaranteePolicyId): Boolean =
        exists("SELECT 1 FROM rates.rate_plan WHERE active AND guarantee_policy_id = :id", id.value)

    override fun ratePackage(id: PackageId): RatePackage? = packages.findById(id.value).orElse(null)

    override fun ratePackageForUpdate(id: PackageId): RatePackage? = forUpdate(RatePackage::class.java, id.value)

    override fun ratePackagesForUpdate(ids: Collection<PackageId>): List<RatePackage> =
        ids
            .map { it.value }
            .distinct()
            .sorted()
            .mapNotNull { forUpdate(RatePackage::class.java, it) }

    override fun ratePackages(ids: List<UUID>): List<RatePackage> =
        inOrder(ids, packages.findAllById(ids)) { it.id.value }

    override fun tryAdd(ratePackage: RatePackage): Boolean =
        unlessViolated(RatesConstraints.PACKAGE_CODE) { packages.saveAndFlush(ratePackage) }

    override fun ratePackageInUse(id: PackageId): Boolean =
        exists(
            "SELECT 1 FROM rates.rate_plan_package l JOIN rates.rate_plan p ON p.id = l.rate_plan_id " +
                "WHERE p.active AND l.package_id = :id",
            id.value,
        )

    override fun ratePlan(id: RatePlanId): RatePlan? = plans.findById(id.value).orElse(null)

    override fun ratePlans(ids: List<UUID>): List<RatePlan> = inOrder(ids, plans.findAllById(ids)) { it.id.value }

    override fun tryAdd(plan: RatePlan): Boolean =
        unlessViolated(RatesConstraints.RATE_PLAN_CODE) {
            plans.saveAndFlush(plan)
        }

    override fun flush() = plans.flush()

    override fun tryFlush(constraint: String): Boolean = unlessViolated(constraint) { plans.flush() }

    /**
     * A linha travada (`SELECT … FOR UPDATE`) e relida: já carregada nesta transação, a entidade traria o estado lido
     * antes da trava.
     */
    private fun <T : Any> forUpdate(
        type: Class<T>,
        id: UUID,
    ): T? = entityManager.find(type, id)?.also { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }

    private fun exists(
        sql: String,
        id: UUID,
    ): Boolean =
        jdbc
            .sql("SELECT EXISTS ($sql)")
            .param("id", id)
            .query(Boolean::class.java)
            .single()

    private fun <T> inOrder(
        ids: List<UUID>,
        found: List<T>,
        idOf: (T) -> UUID,
    ): List<T> {
        val byId = found.associateBy(idOf)
        return ids.mapNotNull(byId::get)
    }
}

/**
 * Violação da unicidade [constraint] (`23505`), reconhecida pelo campo `constraint` do erro do servidor na cadeia de
 * causas, nunca pelo texto, vira `false`; qualquer outra segue.
 */
internal fun unlessViolated(
    constraint: String,
    write: () -> Unit,
): Boolean =
    try {
        write()
        true
    } catch (e: DataIntegrityViolationException) {
        val violated =
            generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<PSQLException>()
                .any { it.sqlState == UNIQUE_VIOLATION && it.serverErrorMessage?.constraint == constraint }
        if (!violated) throw e
        false
    }

private const val UNIQUE_VIOLATION = "23505"
