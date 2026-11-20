package com.zeny.hotel.reporting

import com.zeny.hotel.files.FileId
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * O snapshot diário das estatísticas do hotel numa data operacional (M6 E23, D1). As contagens são **disjuntas** e
 * vêm das noites vendidas que hospedam (`sold` e não day use): [roomsSold] (plano `NORMAL`), [roomsComplimentary]
 * (`COURTESY`) e [roomsHouseUse] (`HOUSE_USE`). [roomsAvailable] é o total menos as UHs fora de ordem do inventário.
 * As receitas são dos lançamentos da data, sem repasse (a taxa de serviço) e sem impostos; [eventsRevenue] é zero até
 * o módulo de eventos. As derivadas: [occupancy] = ([roomsSold] + [roomsComplimentary]) ÷ [roomsAvailable] (2 casas),
 * [adr] = [roomRevenue] ÷ [roomsSold] e [revpar] = [roomRevenue] ÷ [roomsAvailable] (2 casas, `HALF_EVEN`); todas
 * `null` com denominador zero, e a ocupação também acima de 999,9999%.
 */
data class DailyStatisticsSnapshot(
    val businessDate: LocalDate,
    val roomsSold: Long,
    val roomsComplimentary: Long,
    val roomsHouseUse: Long,
    val roomsAvailable: Long,
    val roomsOutOfOrder: Long,
    val arrivals: Long,
    val departures: Long,
    val noShows: Long,
    val cancellations: Long,
    val roomRevenue: Money,
    val foodBeverageRevenue: Money,
    val eventsRevenue: Money,
    val otherRevenue: Money,
    val occupancy: Percent?,
    val adr: Money?,
    val revpar: Money?,
)

/** O tipo de um documento arquivado por data. Hoje só o PDF do fechamento; a lista pode crescer. */
enum class ArchivedReportKind {
    /** O PDF da data: o snapshot, o painel do dia e as pendências do fechamento. */
    DAY_REPORT,
}

/**
 * Um documento arquivado de uma data (`reporting.archived_report`): o [id] da linha é o dono do anexo no `files`, e o
 * [fileId] é o anexo sensível (PDF), baixado pelo `files` com a permissão `reporting.archive.read`.
 */
data class ArchivedReport(
    val id: UUID,
    val businessDate: LocalDate,
    val kind: ArchivedReportKind,
    val fileId: FileId,
    val createdAt: Instant,
)

/**
 * A API pública do `reporting` (M6 E23). As operações não conferem permissão: quem chama (o fechamento do dia) já se
 * autorizou, como nas demais APIs entre módulos.
 */
interface ReportingApi {
    /**
     * O snapshot de [date]: grava `reporting.daily_statistics` na primeira chamada e devolve sempre o gravado, sem
     * recalcular. Roda na transação de quem chama (`REQUIRED`), em `READ COMMITTED`, de que a releitura depende;
     * chamadas simultâneas da mesma data gravam uma linha só
     * (`ON CONFLICT DO NOTHING` e releitura). Sem guarda de data no código: **só para a data que fecha ou anteriores**,
     * porque o snapshot de uma data cujo movimento ainda não terminou ficaria gravado incompleto; quem garante isso é
     * o fechamento do dia, que chama com a data que fecha.
     */
    fun snapshotDailyStatistics(date: LocalDate): DailyStatisticsSnapshot

    /**
     * Arquiva os documentos de [date] (hoje o PDF do fechamento, [ArchivedReportKind.DAY_REPORT]): para cada tipo, numa
     * **transação própria** (`REQUIRES_NEW`, que não vê o que a transação de quem chama ainda não gravou), toma uma
     * trava consultiva por (data, tipo) **antes** de conferir; se o tipo já está arquivado, devolve o gravado; senão
     * obtém o snapshot de [date] (cria se faltar), renderiza o PDF pelo `printing`, grava o anexo sensível pelo
     * `files` com o id da linha como dono e insere a linha do índice, tudo na mesma transação. Chamadas repetidas ou
     * simultâneas da mesma data dão um anexo e uma linha por tipo. A falha de render ou de gravação propaga e desfaz o
     * tipo (sem anexo órfão vinculado), para o fechamento ser retomado. O PDF traz o painel do dia e as pendências **no
     * momento do arquivo**; **só para a data que fecha** (como o snapshot). Não confere permissão.
     */
    fun archiveDayReports(date: LocalDate): List<ArchivedReport>
}
