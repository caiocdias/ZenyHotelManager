package com.zeny.hotel.billing.web

import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.billing.PostingSource
import com.zeny.hotel.billing.PostingType
import com.zeny.hotel.billing.RevenueGroup
import com.zeny.hotel.billing.TransactionCodeId
import com.zeny.hotel.billing.TransactionCodeKey
import com.zeny.hotel.billing.TransactionCodeKind
import com.zeny.hotel.billing.application.FolioOwnerInput
import com.zeny.hotel.billing.application.FolioView
import com.zeny.hotel.billing.application.NewTransactionCodeCommand
import com.zeny.hotel.billing.application.PostingView
import com.zeny.hotel.billing.application.RoutingRuleCommand
import com.zeny.hotel.billing.application.RoutingRuleView
import com.zeny.hotel.billing.application.SplitBy
import com.zeny.hotel.billing.application.SplitPartCommand
import com.zeny.hotel.billing.application.TransactionCodeCommand
import com.zeny.hotel.billing.application.TransactionCodeView
import com.zeny.hotel.billing.application.TransferView
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.ExtensibleEnum
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
    @field:Schema(
        description =
            "Se o grupo de receita é **repasse**, não receita do hotel (RN-10): só `SERVICE_CHARGE`, a taxa de " +
                "serviço repassada aos empregados.",
    )
    val passThrough: Boolean,
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
                view.revenueGroup.passThrough,
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
    @field:Schema(
        description =
            "Tipo do lançamento: `REGULAR`, `REVERSAL` (estorno), `ADJUSTMENT` (ajuste), `TRANSFER_OUT` (saída de " +
                "uma transferência ou divisão), `TRANSFER_IN` (entrada) ou `SERVICE_CHARGE` (taxa de serviço " +
                "lançada junto de um item). A lista pode crescer " +
                "(`x-extensible-enum`): trate um valor desconhecido sem falhar.",
    )
    @field:ExtensibleEnum
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
    @field:Schema(
        description =
            "O que ainda resta deste lançamento depois das correções e transferências (só no normal e na entrada " +
                "de transferência).",
    )
    val remainingAmount: Money?,
    @field:Schema(description = "Roteado: a conta em que o lançamento foi pedido (esta é a conta de destino).")
    val routedFromFolioId: UUID? = null,
    @field:Schema(description = "Roteado: a regra da conta de origem que desviou o lançamento.")
    val routingRuleId: UUID? = null,
    @field:Schema(description = "Entrada de transferência: a saída correspondente.")
    val transferredFromPostingId: UUID? = null,
    @field:Schema(description = "Entrada de transferência: a conta de origem.")
    val transferredFromFolioId: UUID? = null,
    @field:Schema(description = "Saída de transferência: a entrada correspondente.")
    val transferredToPostingId: UUID? = null,
    @field:Schema(description = "Saída de transferência: a conta de destino.")
    val transferredToFolioId: UUID? = null,
    @field:Schema(
        description =
            "Taxa de serviço (`SERVICE_CHARGE`, ou a entrada da taxa de um item transferido): o item nesta conta.",
    )
    val serviceChargeForPostingId: UUID? = null,
    @field:Schema(description = "Taxa de serviço: o percentual aplicado, guardado no lançamento.", example = "10.0000")
    val serviceChargeRate: Percent? = null,
    @field:Schema(description = "Item com taxa de serviço: a taxa dele nesta conta.")
    val serviceChargePostingId: UUID? = null,
    @field:Schema(
        description =
            "Correção ou saída da taxa de serviço feita junto da do item: o lançamento do item que a provocou " +
                "(nula na remoção a pedido do cliente).",
    )
    val triggeredByPostingId: UUID? = null,
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
                view.routedFromFolioId?.value,
                view.routingRuleId?.value,
                view.transferredFromId?.value,
                view.transferredFromFolioId?.value,
                view.transferredToId?.value,
                view.transferredToFolioId?.value,
                view.serviceChargeForId?.value,
                view.serviceChargeRate,
                view.serviceChargeId?.value,
                view.triggeredById?.value,
            )
    }
}

@Schema(description = "Transferir lançamentos inteiros (o que ainda resta de cada um) para outra conta.")
data class TransferRequest(
    @field:NotNull
    val targetFolioId: UUID?,
    @field:Schema(description = "Os lançamentos desta conta, de 1 a 100, sem repetir.")
    @field:NotNull
    @field:Size(min = 1, max = 100)
    val postingIds: List<UUID>?,
    @field:Schema(description = "Motivo (opcional, até 500).")
    @field:Size(max = 500)
    val reason: String? = null,
)

@Schema(description = "Uma parte da divisão: a conta (esta mesma = a parte que fica) e o percentual ou o valor.")
data class SplitPartDto(
    @field:NotNull
    val folioId: UUID?,
    @field:Schema(description = "Só no modo `PERCENT`: de 0,0001 a 100.", example = "50.0000")
    val percent: Percent? = null,
    @field:Schema(description = "Só no modo `AMOUNT`: positivo.", example = "25.00")
    val amount: Money? = null,
) {
    fun toCommand() = SplitPartCommand(FolioId(checkNotNull(folioId)), percent, amount)
}

@Schema(description = "Dividir o que resta de um lançamento entre contas, por percentual ou por valor.")
data class SplitRequest(
    @field:NotNull
    val by: SplitBy?,
    @field:Schema(description = "De 2 a 10 partes, uma por conta, sem repetir.")
    @field:NotNull
    @field:Size(min = 2, max = 10)
    val parts: List<@Valid SplitPartDto>?,
    @field:Schema(description = "Motivo (opcional, até 500).")
    @field:Size(max = 500)
    val reason: String? = null,
)

@Schema(description = "Uma parte movida: o lançamento de onde saiu, a saída (nesta conta) e a entrada (no destino).")
data class TransferredPostingResponse(
    val originalId: UUID,
    val outgoing: PostingResponse,
    val incoming: PostingResponse,
    @field:Schema(description = "Item com taxa de serviço: a saída da parte da taxa que foi junto.")
    val serviceChargeOutgoing: PostingResponse? = null,
    @field:Schema(description = "Item com taxa de serviço: a entrada da parte da taxa, vinculada à entrada do item.")
    val serviceChargeIncoming: PostingResponse? = null,
) {
    companion object {
        fun of(view: TransferView) =
            TransferredPostingResponse(
                view.originalId.value,
                PostingResponse.of(view.outgoing),
                PostingResponse.of(view.incoming),
                view.serviceChargeOutgoing?.let(PostingResponse::of),
                view.serviceChargeIncoming?.let(PostingResponse::of),
            )
    }
}

@Schema(description = "As partes movidas por uma transferência ou divisão.")
data class TransferResponse(
    val transfers: List<TransferredPostingResponse>,
)

@Schema(
    description =
        "Uma regra de roteamento: os códigos (`transactionCodeIds`) **ou** o grupo de receita (`revenueGroup`) e o " +
            "período em datas de negócio.",
)
data class RoutingRuleRequest(
    @field:NotNull
    val targetFolioId: UUID?,
    @field:Schema(description = "Códigos cobertos (de 1 a 50); com eles, sem `revenueGroup`.")
    val transactionCodeIds: List<UUID>? = null,
    @field:Schema(description = "Grupo de receita coberto; com ele, sem `transactionCodeIds`.")
    val revenueGroup: RevenueGroup? = null,
    @field:Schema(description = "Primeira data operacional da regra.")
    @field:NotNull
    val startsOn: LocalDate?,
    @field:Schema(description = "Última data operacional (inclusive); nula: sem fim.")
    val endsOn: LocalDate? = null,
) {
    fun toCommand() =
        RoutingRuleCommand(
            FolioId(checkNotNull(targetFolioId)),
            transactionCodeIds?.map(::TransactionCodeId),
            revenueGroup,
            checkNotNull(startsOn),
            endsOn,
        )
}

@Schema(description = "Uma regra de roteamento nova: a conta de origem (fixa depois) e os dados da regra.")
data class RoutingRuleCreateRequest(
    @field:NotNull
    val sourceFolioId: UUID?,
    @field:NotNull
    val targetFolioId: UUID?,
    @field:Schema(description = "Códigos cobertos (de 1 a 50); com eles, sem `revenueGroup`.")
    val transactionCodeIds: List<UUID>? = null,
    @field:Schema(description = "Grupo de receita coberto; com ele, sem `transactionCodeIds`.")
    val revenueGroup: RevenueGroup? = null,
    @field:Schema(description = "Primeira data operacional da regra.")
    @field:NotNull
    val startsOn: LocalDate?,
    @field:Schema(description = "Última data operacional (inclusive); nula: sem fim.")
    val endsOn: LocalDate? = null,
) {
    fun toCommand() = RoutingRuleRequest(targetFolioId, transactionCodeIds, revenueGroup, startsOn, endsOn).toCommand()
}

@Schema(description = "Uma regra de roteamento.")
data class RoutingRuleResponse(
    val id: UUID,
    val sourceFolioId: UUID,
    val targetFolioId: UUID,
    @field:Schema(description = "Os códigos cobertos (vazio numa regra por grupo).")
    val transactionCodeIds: List<UUID>,
    @field:Schema(description = "O grupo de receita coberto (nulo numa regra por código).")
    val revenueGroup: RevenueGroup?,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
    val active: Boolean,
    val createdAt: Instant,
    val createdBy: UUID?,
    val updatedAt: Instant,
) {
    companion object {
        fun of(view: RoutingRuleView) =
            RoutingRuleResponse(
                view.id.value,
                view.sourceFolioId.value,
                view.targetFolioId.value,
                view.transactionCodeIds.map { it.value },
                view.revenueGroup,
                view.startsOn,
                view.endsOn,
                view.active,
                view.createdAt,
                view.createdBy?.value,
                view.updatedAt,
            )
    }
}
