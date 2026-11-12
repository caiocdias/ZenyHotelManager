package com.zeny.hotel.billing.application

import com.zeny.hotel.billing.BillingPermissions
import com.zeny.hotel.billing.FolioId
import com.zeny.hotel.billing.FolioOwnerDescriber
import com.zeny.hotel.billing.FolioOwnerType
import com.zeny.hotel.billing.FolioStatus
import com.zeny.hotel.guests.GuestsApi
import com.zeny.hotel.printing.DocumentFormat
import com.zeny.hotel.printing.DocumentRequest
import com.zeny.hotel.printing.DocumentTemplate
import com.zeny.hotel.printing.ModuleDocumentTemplates
import com.zeny.hotel.printing.PrintingApi
import com.zeny.hotel.printing.RenderedDocument
import com.zeny.hotel.shared.AuditContext
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A trilha do `print` do extrato (D-E11-14): ação e entidade, com retrato só de ids. */
private const val STATEMENT_PRINT = "billing.statement.print"
private const val STATEMENT_ENTITY = "billing.statement"

/** Os templates de documento do `billing` (D-E11-2): o extrato da conta. */
object BillingDocumentTemplates : ModuleDocumentTemplates {
    override val module = "billing"

    const val STATEMENT = "billing.statement"

    override val templates =
        listOf(
            DocumentTemplate(
                code = STATEMENT,
                title = "Extrato da conta",
                xhtmlTemplate = "templates/billing/statement.xhtml",
                textTemplate = "templates/billing/statement.txt",
            ),
        )
}

/** Registra os templates do `billing` no catálogo do `printing`. */
@Configuration(proxyBeanMethods = false)
class BillingDocumentsConfiguration {
    @Bean
    fun billingDocumentTemplates(): ModuleDocumentTemplates = BillingDocumentTemplates
}

/**
 * O extrato da conta como documento (RF-CTA-12, M5 E11; D-E11-12): monta o modelo a partir do
 * [FolioStatementService] (que lê tudo num só retrato e confere `billing.folios.read`), descreve o dono pelas portas
 * [FolioOwnerDescriber] e entrega ao `printing`. O [pdf] é leitura; o [print] é o caso de uso auditado, só com ids.
 * O extrato [StatementAudience.GUEST] não leva usuário, motivo nem outro dado interno (o modelo só os recebe no
 * [StatementAudience.INTERNAL]).
 */
@Service
@Suppress("LongParameterList") // as portas que o documento lê
class FolioStatementDocuments(
    private val statements: FolioStatementService,
    private val printing: PrintingApi,
    private val guests: GuestsApi,
    describers: List<FolioOwnerDescriber>,
    private val authorization: Authorization,
    private val audit: AuditContext,
) {
    private val describers: Map<FolioOwnerType, FolioOwnerDescriber> =
        describers.groupBy { it.ownerType }.mapValues { (type, found) ->
            require(found.size == 1) { "Mais de um FolioOwnerDescriber para o dono $type" }
            found.single()
        }

    @Transactional(readOnly = true)
    fun pdf(
        id: FolioId,
        audience: StatementAudience,
    ): RenderedDocument = printing.render(request(id, audience), DocumentFormat.PDF)

    @Audited(STATEMENT_PRINT)
    @Transactional
    fun print(
        id: FolioId,
        audience: StatementAudience,
        printerId: UUID,
    ): UUID {
        val job = printing.print(request(id, audience), printerId)
        audit.created(STATEMENT_ENTITY, job) {
            mapOf("folioId" to id.toString(), "printerId" to printerId.toString(), "printJobId" to job.toString())
        }
        return job
    }

    private fun request(
        id: FolioId,
        audience: StatementAudience,
    ): DocumentRequest {
        authorization.require(BillingPermissions.FOLIOS_READ)
        return DocumentRequest(BillingDocumentTemplates.STATEMENT, model(statements.statement(id, audience)))
    }

    private fun model(statement: FolioStatement): Map<String, Any?> {
        val folio = statement.folio
        val internal = statement.audience == StatementAudience.INTERNAL
        val payer = folio.payerCompanyId?.let(guests::company)
        val description = describers[folio.ownerType]?.describe(folio.ownerId)
        return mapOf(
            "internal" to internal,
            "folio" to
                mapOf(
                    "number" to folio.number.toString(),
                    "status" to if (folio.status == FolioStatus.OPEN) "Aberta" else "Encerrada",
                    "openedOn" to folio.openedOn,
                    "payer" to payer?.let { it.tradeName ?: it.legalName },
                ),
            "owner" to
                mapOf(
                    "type" to ownerTypeLabel(folio.ownerType),
                    "label" to description?.label,
                    "guestName" to description?.guestName,
                    "roomNumber" to description?.roomNumber,
                    "arrival" to description?.arrival,
                    "departure" to description?.departure,
                ),
            "days" to statement.days.map(::dayModel),
            "chargesTotal" to statement.chargesTotal,
            "paymentsTotal" to statement.paymentsTotal,
            "balance" to statement.balance,
            "preAuthorizations" to
                statement.preAuthorizations.map {
                    mapOf(
                        "status" to it.status.name,
                        "amount" to it.amount,
                        "capturedAmount" to it.capturedAmount,
                        "releasedAmount" to it.releasedAmount,
                        "cardBrand" to it.cardBrand,
                        "cardLast4" to it.cardLast4,
                    )
                },
            "reopenings" to
                statement.reopenings?.map {
                    mapOf(
                        "reason" to it.reason,
                        "reopenedAt" to it.reopenedAt,
                        "reopenedBy" to it.reopenedBy.toString(),
                    )
                },
        )
    }

    private fun dayModel(day: StatementDay): Map<String, Any?> =
        mapOf(
            "date" to day.businessDate,
            "charges" to day.charges,
            "payments" to day.payments,
            "balanceAtEnd" to day.balanceAtEnd,
            "lines" to
                day.lines.map { line ->
                    mapOf(
                        "description" to line.description,
                        "amount" to line.amount,
                        "quantity" to line.quantity,
                        "unitPrice" to line.unitPrice,
                        "user" to line.internal?.user?.toString(),
                        "reason" to line.internal?.reason,
                    )
                },
        )

    private fun ownerTypeLabel(type: FolioOwnerType): String =
        when (type) {
            FolioOwnerType.RESERVATION -> "Reserva"
            FolioOwnerType.GROUP -> "Grupo"
            FolioOwnerType.EVENT -> "Evento"
            FolioOwnerType.COMPANY -> "Empresa"
            FolioOwnerType.WALK_IN -> "Conta avulsa"
        }
}
