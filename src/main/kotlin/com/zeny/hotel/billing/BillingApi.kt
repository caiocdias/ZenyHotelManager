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
 * fora de uma requisição. Estorno e ajuste não estão aqui: são ações sensíveis de quem opera, pela API HTTP.
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
     */
    fun post(
        folioId: FolioId,
        posting: NewPosting,
    ): PostingSummary
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
 * Grupo de receita do código (relatórios e, na E06, a taxa de serviço como repasse). Lista fixa: um grupo novo exige
 * migração e código (decisão do usuário em 2026-10-17). Fixo desde a criação do código.
 */
enum class RevenueGroup {
    ROOM,
    FOOD_BEVERAGE,
    SERVICE_CHARGE,
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

/** Uma conta com o saldo (a soma dos lançamentos; os pagamentos entram na E08). */
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
 */
data class NewPosting(
    val transactionCodeId: TransactionCodeId,
    val unitPrice: Money,
    val quantity: BigDecimal = BigDecimal.ONE,
    val description: String? = null,
    val source: PostingSource,
)

/** Um lançamento gravado. */
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
)
