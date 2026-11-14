package com.zeny.hotel.endofday.application

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.endofday.domain.EndOfDayIssue
import com.zeny.hotel.endofday.domain.EndOfDayRunId
import com.zeny.hotel.endofday.infrastructure.EndOfDayIssueRepository
import com.zeny.hotel.notifications.NotificationRecipients
import com.zeny.hotel.notifications.NotificationsApi
import com.zeny.hotel.notifications.NotificationsTypes
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Levanta as pendências do fechamento (RF-NOT-01): grava a [EndOfDayIssue] e avisa quem acompanha o fechamento
 * (`endofday.runs.read`) pelo `notifications`. Sem transação própria: roda na de quem chama, então uma pendência
 * desfeita junto com a transação da etapa nunca gera aviso (as da etapa avisam agrupadas ao concluí-la). O aviso leva
 * só a data, o código da pendência e a referência opaca; a mensagem fica na API, para quem tem a permissão (RN-13: o
 * texto vai ao push).
 */
@Component
class EndOfDayIssues(
    private val issues: EndOfDayIssueRepository,
    private val notifications: NotificationsApi,
    private val clock: Clock,
) {
    /** Grava a pendência e avisa já, uma por ocorrência (falha de etapa, etapa fora do registro, fora da janela). */
    @Suppress("LongParameterList") // os campos da pendência
    fun raise(
        runId: EndOfDayRunId,
        businessDate: LocalDate,
        stepName: String?,
        kind: String,
        reference: String?,
        message: String,
    ): EndOfDayIssue {
        val issue = record(runId, stepName, kind, reference, message)
        notice(businessDate, kind, reference ?: NO_REFERENCE)
        return issue
    }

    /** Só grava a pendência, sem avisar: o aviso da etapa sai agrupado, por [notifyGrouped], ao concluí-la. */
    fun record(
        runId: EndOfDayRunId,
        stepName: String?,
        kind: String,
        reference: String?,
        message: String,
    ): EndOfDayIssue {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return issues.save(EndOfDayIssue.open(runId, stepName, kind, reference, message, now))
    }

    /**
     * Avisa uma vez por tipo as [raised] (tipo e referência, na ordem em que foram levantadas): com uma só pendência o
     * texto leva a referência; com mais, a contagem (`"<tipo>: <n> pendência(s)"`), sem referências.
     */
    fun notifyGrouped(
        businessDate: LocalDate,
        raised: List<Pair<String, String?>>,
    ) {
        raised.groupBy({ it.first }, { it.second }).forEach { (kind, references) ->
            val text =
                when (references.size) {
                    1 -> references.single() ?: NO_REFERENCE
                    else -> "${references.size} pendência(s)"
                }
            notice(businessDate, kind, text)
        }
    }

    private fun notice(
        businessDate: LocalDate,
        kind: String,
        text: String,
    ) {
        notifications.notify(
            NotificationsTypes.END_OF_DAY_PENDING,
            "Pendência no fechamento de ${businessDate.format(DATE_FORMAT)}",
            "$kind: $text",
            NotificationRecipients.withPermission(EndOfDayPermissions.RUNS_READ),
        )
    }

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        const val NO_REFERENCE = "—"
    }
}
