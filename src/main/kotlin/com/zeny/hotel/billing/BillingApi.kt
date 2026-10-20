package com.zeny.hotel.billing

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * API pública do `billing` para os módulos de cima (reservas no M4, recepção no M5, PDV no M8…): abrir contas e
 * lançar nelas, e ler códigos e contas. **Não confere permissão** (quem chama já se autorizou); as escritas são
 * auditadas (`billing.folio.open`, `billing.posting.create`) em nome do ator da requisição corrente, ou do sistema
 * fora de uma requisição. Estorno e ajuste não estão aqui: são ações sensíveis de quem opera, pela API HTTP. Desde a
 * E07, também o turno de caixa aberto do usuário ([requireOpenShift]), que os recebimentos exigem, e, desde a E09, as
 * cauções a vencer ([cardPreAuthorizationsExpiring]).
 */
interface BillingApi {
    /** O código de lançamento, ativo ou não; `null` se não existe. */
    fun transactionCode(id: TransactionCodeId): TransactionCodeSummary?

    /** O código pré-carregado da [key] (sempre existe; o gestor pode tê-lo renomeado ou desativado). */
    fun transactionCode(key: TransactionCodeKey): TransactionCodeSummary

    /** A conta com o saldo; `null` se não existe. */
    fun folio(id: FolioId): FolioSummary?

    /** As contas do dono, da mais antiga para a mais nova, com o saldo. */
    fun foliosOf(owner: FolioOwner): List<FolioSummary>

    /**
     * Abre uma conta para o dono, na data operacional (RN-01), com número legível `C-<ano>-<sequência>`. Dono
     * `COMPANY` e pagador são conferidos pela `GuestsApi`: empresa inexistente `400 UNKNOWN_COMPANY`, desativada
     * `422 COMPANY_INACTIVE`. Limite de crédito negativo: `400 BILLING_DATA_INVALID`.
     */
    fun openFolio(folio: NewFolio): FolioSummary

    /**
     * Lança na conta (RF-CTA-02) na data operacional corrente do hotel (RN-01): valor = quantidade × valor unitário,
     * arredondado pela regra de `Money` (RN-02), negativo num código `CREDIT`. Conta inexistente
     * `404 FOLIO_NOT_FOUND`, encerrada `409 FOLIO_CLOSED`; código inexistente `400 UNKNOWN_TRANSACTION_CODE`,
     * desativado `422 TRANSACTION_CODE_INACTIVE`; quantidade, valor ou descrição fora da faixa
     * `400 POSTING_INVALID`. O lançamento nunca muda depois (RN-03).
     *
     * **Roteamento** (RF-CTA-04, M3 E05): uma regra vigente na data operacional da conta pedida (por código, que
     * prevalece, ou por grupo de receita) desvia o lançamento para a conta de destino, seguindo a cadeia até a conta
     * sem regra aplicável; o lançamento devolvido fica na conta final ([PostingSummary.folioId]) com a origem em
     * [PostingSummary.routedFromFolioId] e a regra em [PostingSummary.routingRuleId]. Destino encerrado:
     * `409 ROUTING_TARGET_CLOSED`.
     *
     * **Taxa de serviço** (RF-CTA-14, M3 E06): num código com `serviceChargeApplies`, lança junto, à parte, a taxa no
     * código `TX-SERVICO`, na conta final do item (sem rotear de novo), vinculada a ele, com o percentual informado
     * ([NewPosting.serviceChargeRate]), senão o do código, senão o padrão do hotel, arredondado pela regra de `Money`
     * (nada se arredondar a zero); devolvida em [PostingSummary.serviceCharge]. Código `TX-SERVICO` desativado recusa
     * o item (`422 TRANSACTION_CODE_INACTIVE`), salvo com a taxa zero (percentual zero ou valor que arredonda a zero).
     */
    fun post(
        folioId: FolioId,
        posting: NewPosting,
    ): PostingSummary

    /**
     * O turno de caixa aberto do [user] (RF-CTA-10, H13 do M3, M3 E07): todo recebimento, devolução ou captura (os
     * pagamentos da E08, o PDV no M8) passa por aqui, no turno aberto do próprio usuário, sem informá-lo na
     * requisição. Sem turno aberto: `422 CASHIER_SHIFT_REQUIRED`. Trava a linha do turno (`SELECT … FOR UPDATE`) até
     * o fim da transação de quem chama: um fechamento simultâneo espera o recebimento terminar, e o turno devolvido
     * continua aberto até o commit. Chame **dentro** da transação de escrita do caso de uso (fora dela, ou numa só de
     * leitura, `IllegalStateException`) e trave o turno antes da conta (a ordem das travas evita deadlock). Não confere
     * permissão (quem chama já se autorizou) e não grava nada.
     */
    fun requireOpenShift(user: UserId): CashierShiftSummary

    /** O turno de caixa, aberto ou fechado; `null` se não existe. */
    fun cashierShift(id: CashierShiftId): CashierShiftSummary?

    /**
     * As cauções ainda não encerradas (nem capturadas por inteiro nem liberadas) com a validade em [from] (inclusive) a
     * [to] (exclusive), em ordem de validade, com a situação calculada agora (`EXPIRED` as já vencidas; M3 E09): para o
     * fechamento do dia (M6) listar e a renovação (M7). Até 1000. Não confere permissão.
     */
    fun cardPreAuthorizationsExpiring(
        from: Instant,
        to: Instant,
    ): List<CardPreAuthorizationSummary>
}

/** Id de um código de lançamento. */
@JvmInline
value class TransactionCodeId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TransactionCodeId = TransactionCodeId(UuidV7.generate())
    }
}

/** Id de uma conta (folio). */
@JvmInline
value class FolioId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): FolioId = FolioId(UuidV7.generate())
    }
}

/** Id de uma regra de roteamento (M3 E05). */
@JvmInline
value class RoutingRuleId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RoutingRuleId = RoutingRuleId(UuidV7.generate())
    }
}

/** Id de um lançamento. */
@JvmInline
value class PostingId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): PostingId = PostingId(UuidV7.generate())
    }
}

/**
 * Chave estável dos códigos de lançamento pré-carregados (decisão do usuário em 2026-10-17): os outros módulos
 * acham o código por ela ([BillingApi.transactionCode]), nunca pelo código curto, que o gestor pode trocar.
 */
enum class TransactionCodeKey {
    /** Diária (código inicial `DIARIA`, grupo `ROOM`, sem taxa de serviço). */
    ROOM_CHARGE,

    /** Multa de cancelamento (código inicial `MULTA-CANC`, grupo `FEES`). */
    CANCELLATION_FEE,

    /** Multa de no-show (código inicial `MULTA-NOSHOW`, grupo `FEES`). */
    NO_SHOW_FEE,

    /** Taxa de serviço (código inicial `TX-SERVICO`, grupo `SERVICE_CHARGE`), lançada pela E06. */
    SERVICE_CHARGE,

    /** Alimentos e bebidas (código inicial `AB`, grupo `FOOD_BEVERAGE`, com taxa de serviço). */
    FOOD_BEVERAGE,

    /** Lavanderia (código inicial `LAVANDERIA`, grupo `LAUNDRY`). */
    LAUNDRY,

    /** Taxa de turismo (código inicial `TX-TURISMO`, grupo `TAXES`). */
    TOURISM_FEE,

    /** Lançamento avulso (código inicial `AVULSO`, grupo `OTHER`). */
    MISCELLANEOUS,
}

/**
 * Tipo do código de lançamento: diz a natureza e o sinal do lançamento normal. Fixo desde a criação do código.
 */
enum class TransactionCodeKind {
    /** Débito de consumo ou serviço (diária, A&B, lavanderia); o único que aceita taxa de serviço. */
    CHARGE,

    /** Multa (cancelamento, no-show), débito. */
    PENALTY,

    /** Taxa de serviço (Lei 13.419/2017), débito lançado à parte (E06). */
    SERVICE_CHARGE,

    /** Taxa ou tributo cobrado do hóspede (taxa de turismo), débito. */
    TAX,

    /** Crédito na conta (abatimento, cortesia): o lançamento normal tem valor **negativo**. */
    CREDIT,
}

/**
 * Grupo de receita do código (relatórios e a taxa de serviço como repasse). Lista fixa: um grupo novo exige
 * migração e código (decisão do usuário em 2026-10-17). Fixo desde a criação do código.
 *
 * [passThrough]: o valor lançado no grupo é **repasse**, não receita do hotel (RN-10, M3 E06): só a taxa de serviço
 * ([SERVICE_CHARGE]), repassada aos empregados (Lei 13.419/2017); o relatório de arrecadação (M6/M10) a separa da
 * receita por aqui.
 */
enum class RevenueGroup(
    val passThrough: Boolean = false,
) {
    ROOM,
    FOOD_BEVERAGE,
    SERVICE_CHARGE(passThrough = true),
    FEES,
    TAXES,
    LAUNDRY,
    OTHER,
}

/**
 * Tipo do dono de uma conta (decisão do usuário em 2026-10-17): lista fixa, um tipo novo entra por migração e
 * código. O id é opaco: o `billing` não conhece reservas, grupos nem eventos; só `COMPANY` é conferido pela
 * `GuestsApi`.
 */
enum class FolioOwnerType {
    RESERVATION,
    GROUP,
    EVENT,
    COMPANY,

    /** Conta avulsa (sem reserva); sem id informado, o dono é a própria conta. */
    WALK_IN,
}

/** O dono de uma conta: tipo e id opaco (ARCHITECTURE §3.2). */
data class FolioOwner(
    val type: FolioOwnerType,
    val id: UUID,
)

/** Situação da conta. O encerramento e a reabertura são da E10. */
enum class FolioStatus {
    OPEN,
    CLOSED,
}

/** Origem de um lançamento (RF-CTA-02). Pela API HTTP, sempre `MANUAL`. */
enum class PostingSource {
    MANUAL,
    POS,
    END_OF_DAY,
    LAUNDRY,
    EVENT,
}

/** Tipo de lançamento: o normal e as correções vinculadas a um normal (RF-CTA-03). */
enum class PostingType {
    REGULAR,

    /** Estorno: o inverso de tudo o que ainda resta do original (o original menos os ajustes). Um por original. */
    REVERSAL,

    /** Ajuste parcial: sinal oposto ao do original, até o que ainda resta dele. */
    ADJUSTMENT,

    /**
     * Saída de uma transferência ou divisão (RF-CTA-05, M3 E05): na conta de origem, o inverso da parte transferida
     * do lançamento citado (`correctsId`), que continua intacto.
     */
    TRANSFER_OUT,

    /**
     * Entrada de uma transferência ou divisão: na conta de destino, a parte transferida, vinculada à saída
     * (`transferredFromId`); é estornada, ajustada e transferida como um lançamento normal. A entrada da taxa de
     * serviço de um item transferido também cita a entrada do item (`serviceChargeForId`).
     */
    TRANSFER_IN,

    /**
     * Taxa de serviço (RF-CTA-14, RN-10, M3 E06): lançada pelo `billing` junto de um item cujo código a aceita, no
     * código `TX-SERVICO` (`TransactionCodeKey.SERVICE_CHARGE`), na mesma conta do item, vinculada a ele
     * (`serviceChargeForId`) e com o percentual aplicado guardado (`serviceChargeRate`). Acompanha o item no estorno,
     * no ajuste, na transferência e na divisão; à parte, só sai pela remoção a pedido do cliente (um estorno).
     */
    SERVICE_CHARGE,
}

/** Um código de lançamento para os módulos de cima. [serviceChargeRate] nulo: cai na padrão do hotel (H4). */
data class TransactionCodeSummary(
    val id: TransactionCodeId,
    val key: TransactionCodeKey?,
    val code: String,
    val name: String,
    val kind: TransactionCodeKind,
    val revenueGroup: RevenueGroup,
    val serviceChargeApplies: Boolean,
    val serviceChargeRate: Percent?,
    val active: Boolean,
)

/** Uma conta com o saldo: a soma dos lançamentos menos o recebido (pagamentos menos reembolsos, desde a E08). */
data class FolioSummary(
    val id: FolioId,
    val number: DocumentNumber,
    val owner: FolioOwner,
    val payerCompanyId: CompanyId?,
    val creditLimit: Money?,
    val status: FolioStatus,
    val openedOn: LocalDate,
    val balance: Money,
)

/**
 * Uma conta nova. [owner] com `WALK_IN` aceita id nulo (o dono passa a ser a própria conta); os outros tipos exigem
 * o id. [creditLimit] nulo: sem limite.
 */
data class NewFolio(
    val ownerType: FolioOwnerType,
    val ownerId: UUID?,
    val payerCompanyId: CompanyId? = null,
    val creditLimit: Money? = null,
)

/**
 * Um lançamento novo: quantidade positiva com até 3 casas (até 9999,999), valor unitário positivo (até
 * 1.000.000,00), descrição opcional (até 200; vazia, o nome do código) e a origem.
 *
 * [serviceChargeRate] (M3 E06, H4): o percentual da taxa de serviço informado pelo módulo de origem (o `pos`, no M8,
 * passa o do ponto de venda), de 0 a 100 (fora, `400 POSTING_INVALID`, campo `serviceChargeRate`). Só vale num código
 * que aceita a taxa (`serviceChargeApplies`); nulo, vale o do código, senão o padrão do hotel. Num código sem a taxa,
 * é ignorado.
 */
data class NewPosting(
    val transactionCodeId: TransactionCodeId,
    val unitPrice: Money,
    val quantity: BigDecimal = BigDecimal.ONE,
    val description: String? = null,
    val source: PostingSource,
    val serviceChargeRate: Percent? = null,
)

/**
 * Um lançamento gravado. Roteado (M3 E05): [folioId] é a conta de destino, [routedFromFolioId] a conta em que foi
 * pedido e [routingRuleId] a regra dela que o desviou. A entrada de uma transferência cita a saída em
 * [transferredFromId]. Taxa de serviço (M3 E06): [serviceChargeForId] é o item, [serviceChargeRate] o percentual
 * aplicado; no item devolvido por [BillingApi.post], [serviceCharge] é a taxa lançada junto (nula sem taxa).
 */
data class PostingSummary(
    val id: PostingId,
    val folioId: FolioId,
    val transactionCodeId: TransactionCodeId,
    val type: PostingType,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val amount: Money,
    val source: PostingSource,
    val businessDate: LocalDate,
    val postedAt: Instant,
    val postedBy: UserId?,
    val correctsId: PostingId?,
    val routedFromFolioId: FolioId? = null,
    val routingRuleId: RoutingRuleId? = null,
    val transferredFromId: PostingId? = null,
    val serviceChargeForId: PostingId? = null,
    val serviceChargeRate: Percent? = null,
    val serviceCharge: PostingSummary? = null,
)
