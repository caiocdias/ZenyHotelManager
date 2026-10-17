package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.billing.application.FolioOwnerInput
import com.zeny.hotel.billing.application.FolioView
import com.zeny.hotel.billing.application.NewTransactionCodeCommand
import com.zeny.hotel.billing.application.PostingView
import com.zeny.hotel.billing.application.TransactionCodeCommand
import com.zeny.hotel.billing.application.TransactionCodeView
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Um código de lançamento novo; o tipo e o grupo de receita não mudam depois.")
data class TransactionCodeCreateRequest(
    @field:Schema(description = "Letras, dígitos, `_` e `-`, até 20; guardado em maiúsculas.", example = "FRIGOBAR")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:Schema(example = "Frigobar")
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    @field:Schema(description = "Natureza do lançamento; `CREDIT` lança valor negativo.")
    @field:NotNull
    val kind: TransactionCodeKind?,
    @field:NotNull
    val revenueGroup: RevenueGroup?,
    @field:Schema(description = "Se a taxa de serviço se aplica (só em `CHARGE`).")
    val serviceChargeApplies: Boolean = false,
    @field:Schema(
        description = "Taxa de serviço própria (0 a 100), só com a taxa aplicável; nula: a padrão do hotel.",
        example = "10.0000",
    )
    val serviceChargeRate: Percent? = null,
    @field:Schema(description = "Classificação fiscal em texto livre (até o perfil fiscal do M7).")
    @field:Size(max = 60)
    val fiscalClassification: String? = null,
) {
    fun toCommand() =
        NewTransactionCodeCommand(
            checkNotNull(kind),
            checkNotNull(revenueGroup),
            TransactionCodeCommand(
                checkNotNull(code),
                checkNotNull(name),
                serviceChargeApplies,
                serviceChargeRate,
                fiscalClassification,
            ),
        )
}

@Schema(description = "Os dados editáveis de um código de lançamento (o tipo e o grupo de receita são fixos).")
data class TransactionCodeUpdateRequest(
    @field:Schema(description = "Letras, dígitos, `_` e `-`, até 20; guardado em maiúsculas.", example = "FRIGOBAR")
    @field:NotBlank
    @field:Size(max = 20)
    val code: String?,
    @field:NotBlank
    @field:Size(max = 80)
    val name: String?,
    val serviceChargeApplies: Boolean = false,
    @field:Schema(description = "Taxa de serviço própria (0 a 100); nula: a padrão do hotel.", example = "10.0000")
    val serviceChargeRate: Percent? = null,
    @field:Size(max = 60)
    val fiscalClassification: String? = null,
) {
    fun toCommand() =
        TransactionCodeCommand(
            checkNotNull(code),
            checkNotNull(name),
            serviceChargeApplies,
            serviceChargeRate,
            fiscalClassification,
        )
}

@Schema(description = "Um código de lançamento.")
data class TransactionCodeResponse(
    val id: UUID,
    @field:Schema(description = "Chave estável dos oito códigos pré-carregados; nula nos criados pela API.")
    val key: TransactionCodeKey?,
    val code: String,
    val name: String,
    val kind: TransactionCodeKind,
    val revenueGroup: RevenueGroup,
    val serviceChargeApplies: Boolean,
    val serviceChargeRate: Percent?,
    val fiscalClassification: String?,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: TransactionCodeView) =
            TransactionCodeResponse(
                view.id.value,
                view.key,
                view.code,
                view.name,
                view.kind,
                view.revenueGroup,
                view.serviceChargeApplies,
                view.serviceChargeRate,
                view.fiscalClassification,
                view.active,
                view.createdAt,
                view.updatedAt,
            )
    }
}

@Schema(description = "O dono de uma conta: tipo e id opaco (o `billing` não conhece reservas).")
data class FolioOwnerDto(
    @field:NotNull
    val type: FolioOwnerType?,
    @field:Schema(description = "Id do dono; só a conta avulsa (`WALK_IN`) dispensa (o dono passa a ser a conta).")
    val id: UUID? = null,
)

@Schema(description = "Uma conta nova.")
data class FolioOpenRequest(
    @field:NotNull
    @field:Valid
    val owner: FolioOwnerDto?,
    @field:Schema(description = "Empresa pagadora (opcional).")
    val payerCompanyId: UUID? = null,
    @field:Schema(description = "Limite de crédito (não negativo); nulo: sem limite.", example = "1500.00")
    val creditLimit: Money? = null,
) {
    fun ownerInput() = FolioOwnerInput(checkNotNull(checkNotNull(owner).type), owner.id)
}

@Schema(description = "O pagador e o limite de crédito de uma conta (o dono não muda).")
data class FolioUpdateRequest(
    val payerCompanyId: UUID? = null,
    @field:Schema(example = "1500.00")
    val creditLimit: Money? = null,
)

@Schema(description = "Uma conta, com o saldo (a soma dos lançamentos).")
data class FolioResponse(
    val id: UUID,
    @field:Schema(description = "Número legível da conta, como `C-2026-000123`.")
    val number: DocumentNumber,
    val owner: FolioOwnerDto,
    val payerCompanyId: UUID?,
    val creditLimit: Money?,
    val status: FolioStatus,
    val balance: Money,
    @field:Schema(description = "Saldo acima do limite de crédito (sem limite, sempre falso).")
    val creditLimitExceeded: Boolean,
    @field:Schema(description = "Data operacional da abertura.")
    val openedOn: LocalDate,
    val openedAt: Instant,
    val openedBy: UUID?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: FolioView) =
            FolioResponse(
                view.id.value,
                view.number,
                FolioOwnerDto(view.ownerType, view.ownerId),
                view.payerCompanyId?.value,
                view.creditLimit,
                view.status,
                view.balance,
                view.creditLimitExceeded,
                view.openedOn,
                view.openedAt,
                view.openedBy?.value,
                view.updatedAt,
            )
    }
}

@Schema(description = "Um lançamento manual: valor = quantidade × valor unitário, arredondado pela regra de `Money`.")
data class PostingRequest(
    @field:NotNull
    val transactionCodeId: UUID?,
    @field:Schema(description = "Quantidade positiva, até 3 casas (padrão 1).", example = "2")
    val quantity: BigDecimal = BigDecimal.ONE,
    @field:Schema(description = "Valor unitário positivo.", example = "250.00")
    @field:NotNull
    val unitPrice: Money?,
    @field:Schema(description = "Descrição (até 200); vazia: o nome do código.")
    @field:Size(max = 200)
    val description: String? = null,
)

@Schema(description = "O motivo do estorno.")
data class ReversalRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
)

@Schema(description = "Um ajuste parcial: o valor (positivo) sai do lançamento, com o sinal oposto ao dele.")
data class AdjustmentRequest(
    @field:Schema(description = "Valor do ajuste, positivo, até o que ainda resta do lançamento.", example = "20.00")
    @field:NotNull
    val amount: Money?,
    @field:NotBlank
    @field:Size(max = 500)
    val reason: String?,
)

@Schema(description = "Um lançamento (imutável).")
data class PostingResponse(
    val id: UUID,
    val folioId: UUID,
    val transactionCodeId: UUID,
    val type: PostingType,
    val quantity: BigDecimal,
    val unitPrice: Money,
    @field:Schema(description = "Valor com sinal: débito positivo, crédito e correção de débito negativos.")
    val amount: Money,
    val description: String,
    val source: PostingSource,
    @field:Schema(description = "Data operacional do hotel no lançamento (RN-01).")
    val businessDate: LocalDate,
    val postedAt: Instant,
    @field:Schema(description = "Quem lançou; nulo: o sistema.")
    val postedBy: UUID?,
    @field:Schema(description = "O lançamento corrigido (só no estorno e no ajuste).")
    val correctsId: UUID?,
    @field:Schema(description = "Motivo da correção (só no estorno e no ajuste).")
    val reason: String?,
    @field:Schema(description = "A aprovação de superior que autorizou a correção, se houve.")
    val approvalId: UUID?,
    val approvedBy: UUID?,
    @field:Schema(description = "O estorno deste lançamento, se houve (só no normal).")
    val reversedBy: UUID?,
    @field:Schema(description = "O que ainda resta deste lançamento depois das correções (só no normal).")
    val remainingAmount: Money?,
) {
    companion object {
        fun of(view: PostingView) =
            PostingResponse(
                view.id.value,
                view.folioId.value,
                view.transactionCodeId.value,
                view.type,
                view.quantity,
                view.unitPrice,
                view.amount,
                view.description,
                view.source,
                view.businessDate,
                view.postedAt,
                view.postedBy?.value,
                view.correctsId?.value,
                view.reason,
                view.approvalId,
                view.approvedBy?.value,
                view.reversedBy?.value,
                view.remaining,
            )
    }
}
