package com.zeny.hotel.billing.domain

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.billing.CashMovementId
import com.zeny.hotel.billing.CashMovementType
import com.zeny.hotel.billing.CashPointId
import com.zeny.hotel.billing.CashierShiftId
import com.zeny.hotel.billing.CashierShiftStatus
import com.zeny.hotel.billing.PaymentMethod
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.sum
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Ponto de caixa (RF-CTA-09, SPEC §8.2.7): recepção, restaurante, bar… O nome é único sem diferenciar maiúsculas (o
 * banco garante). [blindClose] (padrão ligado; decisão do usuário em 2026-10-19): o fechamento dos turnos é cego, o
 * operador não vê o valor esperado antes de declarar a contagem; o turno guarda o valor da abertura. Desativado, não
 * abre turno (os abertos seguem até o fechamento). Nunca é apagado.
 */
@Entity
@Table(name = "cash_point", schema = "billing")
class CashPoint private constructor(
    @Id
    val id: CashPointId,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(name = "blind_close", nullable = false)
    var blindClose: Boolean = true
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Troca o nome e o modo de fechamento; `true` se algo mudou. */
    fun update(
        name: String,
        blindClose: Boolean,
        now: Instant,
    ): Boolean {
        val trimmed = validName(name)
        if (trimmed == this.name && blindClose == this.blindClose) return false
        this.name = trimmed
        this.blindClose = blindClose
        updatedAt = now
        return true
    }

    fun deactivate(now: Instant): Boolean = setActive(false, now)

    fun reactivate(now: Instant): Boolean = setActive(true, now)

    private fun setActive(
        value: Boolean,
        now: Instant,
    ): Boolean {
        if (active == value) return false
        active = value
        updatedAt = now
        return true
    }

    override fun toString(): String = "CashPoint(id=$id)"

    companion object {
        const val MAX_NAME_LENGTH = 80

        fun create(
            name: String,
            blindClose: Boolean,
            now: Instant,
        ): CashPoint =
            CashPoint(CashPointId.new(), now).also {
                it.name = validName(name)
                it.blindClose = blindClose
            }

        private fun validName(name: String): String =
            BillingRules.required("name", name, MAX_NAME_LENGTH, BillingErrorCodes.BILLING_DATA_INVALID)
    }
}

/**
 * Turno de caixa (RF-CTA-09 e 10, SPEC §8.2.7): um operador num ponto de caixa, com o fundo de troco (em dinheiro)
 * contado na abertura. No máximo um aberto por operador, em qualquer ponto (H13; o banco garante). Recebe sangrias e
 * suprimentos ([CashMovement]) enquanto aberto e fecha com a contagem declarada por forma de pagamento
 * ([CashierClosing]); fechado, não muda mais (o banco recusa). O dono, para o escopo `OWN`, é o operador. Nunca é
 * apagado.
 */
@Entity
@Table(name = "cashier_shift", schema = "billing")
@Suppress("LongParameterList") // os dados fixos da abertura
class CashierShift private constructor(
    @Id
    val id: CashierShiftId,
    @Column(nullable = false, updatable = false)
    val number: DocumentNumber,
    @Column(name = "cash_point_id", nullable = false, updatable = false)
    private val cashPointValue: UUID,
    @Column(name = "operator_id", nullable = false, updatable = false)
    private val operatorValue: UUID,
    @Column(name = "blind_close", nullable = false, updatable = false)
    val blindClose: Boolean,
    @Column(name = "opening_float", nullable = false, updatable = false)
    val openingFloat: Money,
    @Column(name = "opened_on", nullable = false, updatable = false)
    val openedOn: LocalDate,
    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant,
) : OwnedResource {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CashierShiftStatus = CashierShiftStatus.OPEN
        protected set

    @Column(name = "closed_on")
    var closedOn: LocalDate? = null
        protected set

    @Column(name = "closed_at")
    var closedAt: Instant? = null
        protected set

    @Column(name = "difference_total")
    var differenceTotal: Money? = null
        protected set

    @Version
    var version: Long? = null
        protected set

    val cashPointId: CashPointId get() = CashPointId(cashPointValue)

    val operatorId: UserId get() = UserId(operatorValue)

    override val ownerIds: Set<UserId> get() = setOf(operatorId)

    /** Exige o turno aberto (movimento, fechamento). */
    fun requireOpen() {
        if (status != CashierShiftStatus.OPEN) {
            throw ConflictException(BillingErrorCodes.CASHIER_SHIFT_CLOSED, "O turno de caixa $number já foi fechado.")
        }
    }

    /** Só o operador registra movimentos e fecha o turno, mesmo quem consulta todos os turnos. */
    fun requireOperator(user: UserId) {
        if (user != operatorId) {
            throw PermissionDeniedException(
                BillingErrorCodes.CASHIER_SHIFT_OPERATOR_REQUIRED,
                "Só o operador do turno de caixa registra movimentos e o fecha.",
            )
        }
    }

    /**
     * Se o valor esperado é visível a quem consulta (o fechamento cego, decisão do usuário em 2026-10-19): sempre
     * depois do fechamento e num turno não cego; num turno cego aberto, só a quem confere os turnos ([reviewer]).
     */
    fun expectedVisible(reviewer: Boolean): Boolean = status == CashierShiftStatus.CLOSED || !blindClose || reviewer

    /**
     * Fecha o turno com a contagem declarada ([declared], valores por forma; as omitidas valem zero) contra o
     * esperado ([expectation]): devolve uma contagem por forma de pagamento, com a diferença (declarado − esperado).
     */
    fun close(
        declared: Map<PaymentMethod, Money>,
        expectation: ShiftExpectation,
        closedOn: LocalDate,
        now: Instant,
    ): CashierClosing {
        requireOpen()
        val counts =
            PaymentMethod.entries.map { method ->
                ShiftCount(method, declared[method] ?: Money.ZERO, expectation.of(method).expected)
            }
        val closing = CashierClosing(counts)
        status = CashierShiftStatus.CLOSED
        this.closedOn = closedOn
        closedAt = now
        differenceTotal = closing.differenceTotal
        return closing
    }

    override fun toString(): String = "CashierShift(id=$id, number=$number)"

    companion object {
        /** Teto do fundo de troco, de um movimento e de cada valor declarado. */
        val MAX_AMOUNT: Money = Money.of("1000000.00")

        /** Abre um turno no ponto (ativo) para o operador, com o fundo de troco (zero ou positivo). */
        @Suppress("LongParameterList") // os dados da abertura
        fun open(
            number: DocumentNumber,
            cashPoint: CashPoint,
            operator: UserId,
            openingFloat: Money,
            businessDate: LocalDate,
            now: Instant,
        ): CashierShift {
            requireActive(cashPoint)
            if (openingFloat.isNegative() || openingFloat > MAX_AMOUNT) {
                throw BillingRules.invalid(
                    BillingErrorCodes.CASHIER_SHIFT_INVALID,
                    "openingFloat",
                    "O fundo de troco vai de 0,00 a 1.000.000,00.",
                )
            }
            return CashierShift(
                CashierShiftId.new(),
                number,
                cashPoint.id.value,
                operator.value,
                cashPoint.blindClose,
                openingFloat,
                businessDate,
                now,
            )
        }

        fun requireActive(cashPoint: CashPoint) {
            if (!cashPoint.active) {
                throw BusinessRuleException(
                    BillingErrorCodes.CASH_POINT_INACTIVE,
                    "O ponto de caixa ${cashPoint.name} está desativado.",
                )
            }
        }
    }
}

/**
 * Sangria ou suprimento (RF-CTA-09, RN-03): sempre em dinheiro e com valor positivo (decisão do usuário em
 * 2026-10-19), com o motivo. Fato imutável, guardado à parte do turno (o banco recusa mudança e exclusão).
 */
data class CashMovement(
    val id: CashMovementId,
    val shiftId: CashierShiftId,
    val type: CashMovementType,
    val amount: Money,
    val reason: String,
    val businessDate: LocalDate,
    val recordedAt: Instant,
    val recordedBy: UserId,
) {
    /** O efeito no dinheiro da gaveta: o suprimento soma, a sangria tira. */
    val cashEffect: Money get() = if (type == CashMovementType.SUPPLY) amount else -amount

    override fun toString(): String = "CashMovement(id=$id, shiftId=$shiftId, type=$type)"

    companion object {
        const val MAX_REASON_LENGTH = 200

        /** Um movimento no turno aberto, registrado pelo operador. */
        @Suppress("LongParameterList") // os dados do movimento
        fun record(
            shift: CashierShift,
            type: CashMovementType,
            amount: Money,
            reason: String?,
            businessDate: LocalDate,
            now: Instant,
            by: UserId,
        ): CashMovement {
            shift.requireOperator(by)
            shift.requireOpen()
            if (!amount.isPositive() || amount > CashierShift.MAX_AMOUNT) {
                throw BillingRules.invalid(
                    BillingErrorCodes.CASHIER_SHIFT_INVALID,
                    "amount",
                    "O valor do movimento vai de 0,01 a 1.000.000,00.",
                )
            }
            val text =
                BillingRules.required(
                    "reason",
                    reason,
                    MAX_REASON_LENGTH,
                    BillingErrorCodes.CASHIER_SHIFT_INVALID,
                )
            return CashMovement(CashMovementId.new(), shift.id, type, amount, text, businessDate, now, by)
        }
    }
}

/**
 * O que se espera numa forma de pagamento no fechamento: no dinheiro, o fundo de troco, os suprimentos menos as
 * sangrias e os pagamentos em dinheiro; nas demais, só os pagamentos (decisão do usuário em 2026-10-19; os pagamentos
 * chegam na E08, até lá zero).
 */
data class MethodExpectation(
    val method: PaymentMethod,
    val openingFloat: Money = Money.ZERO,
    val supplies: Money = Money.ZERO,
    val withdrawals: Money = Money.ZERO,
    val payments: Money = Money.ZERO,
) {
    val expected: Money get() = openingFloat + supplies - withdrawals + payments
}

/** O esperado de um turno, uma linha por forma de pagamento (todas, na ordem de [PaymentMethod]). */
data class ShiftExpectation(
    val methods: List<MethodExpectation>,
) {
    fun of(method: PaymentMethod): MethodExpectation = methods.single { it.method == method }

    val expectedTotal: Money get() = methods.map { it.expected }.sum()

    companion object {
        /**
         * O esperado a partir do fundo de troco e dos movimentos do turno (só dinheiro) e dos pagamentos por forma
         * ([payments]; vazio até a E08). A sangria nunca é recusada por passar do dinheiro esperado: isso revelaria o
         * esperado ao operador num fechamento cego; o resultado negativo aparece como diferença.
         */
        fun of(
            openingFloat: Money,
            movements: List<CashMovement>,
            payments: Map<PaymentMethod, Money> = emptyMap(),
        ): ShiftExpectation =
            ShiftExpectation(
                PaymentMethod.entries.map { method ->
                    if (method == PaymentMethod.CASH) {
                        MethodExpectation(
                            method,
                            openingFloat,
                            movements.filter { it.type == CashMovementType.SUPPLY }.map { it.amount }.sum(),
                            movements.filter { it.type == CashMovementType.WITHDRAWAL }.map { it.amount }.sum(),
                            payments[method] ?: Money.ZERO,
                        )
                    } else {
                        MethodExpectation(method, payments = payments[method] ?: Money.ZERO)
                    }
                },
            )
    }
}

/** A contagem de uma forma de pagamento no fechamento: o declarado, o esperado e a diferença (declarado − esperado). */
data class ShiftCount(
    val method: PaymentMethod,
    val declared: Money,
    val expected: Money,
) {
    val difference: Money get() = declared - expected
}

/** O fechamento: uma contagem por forma de pagamento e os totais. */
data class CashierClosing(
    val counts: List<ShiftCount>,
) {
    val declaredTotal: Money get() = counts.map { it.declared }.sum()
    val expectedTotal: Money get() = counts.map { it.expected }.sum()
    val differenceTotal: Money get() = counts.map { it.difference }.sum()

    /** Alguma forma com diferença (qualquer diferença diferente de zero; sem tolerância, decisão do usuário). */
    val hasDifference: Boolean get() = counts.any { !it.difference.isZero() }

    /** As formas com diferença, na ordem de [PaymentMethod]. */
    val differences: List<ShiftCount> get() = counts.filter { !it.difference.isZero() }

    companion object {
        /**
         * Confere a contagem declarada (uma entrada por forma, valor de 0,00 a 1.000.000,00): forma repetida ou valor
         * fora da faixa, `400 CASHIER_COUNT_INVALID` com o campo (`counts[1].method`, `counts[0].amount`).
         */
        fun declared(entries: List<Pair<PaymentMethod, Money>>): Map<PaymentMethod, Money> {
            val violations =
                entries.flatMapIndexed { index, (method, amount) ->
                    buildList {
                        if (entries.take(index).any { it.first == method }) {
                            add(violation("counts[$index].method", "A forma $method aparece mais de uma vez."))
                        }
                        if (amount.isNegative() || amount > CashierShift.MAX_AMOUNT) {
                            add(violation("counts[$index].amount", "O valor declarado vai de 0,00 a 1.000.000,00."))
                        }
                    }
                }
            if (violations.isNotEmpty()) {
                throw InvalidInputException(
                    BillingErrorCodes.CASHIER_COUNT_INVALID,
                    "A contagem tem forma repetida ou valor fora da faixa.",
                    violations,
                )
            }
            return entries.toMap()
        }

        private fun violation(
            field: String,
            message: String,
        ) = FieldViolation(field, BillingErrorCodes.CASHIER_COUNT_INVALID.value, message)
    }
}
