package com.zeny.hotel.reporting.application

import com.zeny.hotel.reporting.application.ReportColumnType.DATE
import com.zeny.hotel.reporting.application.ReportColumnType.DECIMAL
import com.zeny.hotel.reporting.application.ReportColumnType.INSTANT
import com.zeny.hotel.reporting.application.ReportColumnType.INTEGER
import com.zeny.hotel.reporting.application.ReportColumnType.TEXT

/*
 * A conversão do resultado de cada caso de uso (o mesmo que a resposta JSON devolve) no modelo tabular da exportação
 * (D6). Cada tabela leva uma linha por item da lista do relatório, na mesma ordem do JSON, com os mesmos campos; o
 * dinheiro e o percentual saem como decimal, os ids como texto. Os cabeçalhos são em pt-BR.
 */

private fun column(
    key: String,
    label: String,
    type: ReportColumnType,
) = ReportColumn(key, label, type)

fun HousekeepingReport.toDocument(baseName: String) =
    ReportDocument(
        baseName,
        listOf(
            ReportSheet(
                "Camareiras",
                ReportTable(
                    listOf(
                        column("employeeId", "Colaborador", TEXT),
                        column("rooms", "UHs limpas", INTEGER),
                        column("credits", "Créditos", INTEGER),
                    ),
                    attendants.map { listOf(it.employeeId.toString(), it.rooms, it.credits) },
                ),
            ),
            ReportSheet(
                "Tipos de limpeza",
                ReportTable(
                    listOf(
                        column("cleaningTypeId", "Tipo de limpeza", TEXT),
                        column("roomTypeId", "Tipo de UH", TEXT),
                        column("tasks", "Limpezas", INTEGER),
                        column("averageMinutes", "Tempo médio (min)", DECIMAL),
                        column("inspections", "Inspeções", INTEGER),
                        column("rejections", "Reprovadas", INTEGER),
                        column("rejectionRate", "Taxa de reprovação (%)", DECIMAL),
                    ),
                    cleaningTypes.map {
                        listOf(
                            it.cleaningTypeId.toString(),
                            it.roomTypeId.toString(),
                            it.tasks,
                            it.averageMinutes,
                            it.inspections,
                            it.rejections,
                            it.rejectionRate?.value,
                        )
                    },
                ),
            ),
            ReportSheet(
                "Inspeções voluntárias",
                ReportTable(
                    listOf(
                        column("inspections", "Inspeções", INTEGER),
                        column("rejections", "Reprovadas", INTEGER),
                        column("rejectionRate", "Taxa de reprovação (%)", DECIMAL),
                    ),
                    listOf(
                        listOf(
                            voluntaryInspections.inspections,
                            voluntaryInspections.rejections,
                            voluntaryInspections.rejectionRate?.value,
                        ),
                    ),
                ),
            ),
        ),
    )

fun MaintenanceReport.toDocument(baseName: String) =
    ReportDocument(
        baseName,
        listOf(
            ReportSheet(
                "Por status",
                ReportTable(
                    listOf(column("status", "Status", TEXT), column("orders", "Ordens", INTEGER)),
                    byStatus.map { listOf(it.status.name, it.orders) },
                ),
            ),
            ReportSheet(
                "Por categoria",
                ReportTable(
                    listOf(column("category", "Categoria", TEXT), column("orders", "Ordens", INTEGER)),
                    byCategory.map { listOf(it.category.name, it.orders) },
                ),
            ),
            ReportSheet(
                "Concluídas",
                ReportTable(
                    listOf(
                        column("orders", "Ordens", INTEGER),
                        column("averageHours", "Tempo médio (h)", DECIMAL),
                        column("laborCost", "Mão de obra", DECIMAL),
                        column("thirdPartyCost", "Terceiros", DECIMAL),
                        column("partsCost", "Peças", DECIMAL),
                        column("totalCost", "Total", DECIMAL),
                    ),
                    listOf(
                        listOf(
                            completed.orders,
                            completed.averageHours,
                            completed.laborCost.amount,
                            completed.thirdPartyCost.amount,
                            completed.partsCost.amount,
                            completed.totalCost.amount,
                        ),
                    ),
                ),
            ),
        ),
    )

fun DayPanel.toDocument(baseName: String) =
    ReportDocument(
        baseName,
        listOf(
            ReportSheet(
                "Dias",
                ReportTable(
                    listOf(
                        column("date", "Data", DATE),
                        column("arrivals", "Chegadas", INTEGER),
                        column("departures", "Saídas", INTEGER),
                        column("hosted", "Hospedados", INTEGER),
                        column("occupancy", "Ocupação (%)", DECIMAL),
                    ),
                    days.map { listOf(it.date, it.arrivals, it.departures, it.hosted, it.occupancy?.value) },
                ),
            ),
            ReportSheet(
                "UHs de hoje",
                ReportTable(
                    listOf(
                        column("active", "Ativas", INTEGER),
                        column("occupied", "Ocupadas", INTEGER),
                        column("vacant", "Vagas", INTEGER),
                        column("dirty", "Sujas", INTEGER),
                        column("clean", "Limpas", INTEGER),
                        column("inspected", "Inspecionadas", INTEGER),
                        column("inspectionPending", "À espera de inspeção", INTEGER),
                        column("outOfOrder", "Fora de ordem", INTEGER),
                        column("outOfService", "Fora de serviço", INTEGER),
                    ),
                    listOf(
                        listOf(
                            rooms.active,
                            rooms.occupied,
                            rooms.vacant,
                            rooms.dirty,
                            rooms.clean,
                            rooms.inspected,
                            rooms.inspectionPending,
                            rooms.outOfOrder,
                            rooms.outOfService,
                        ),
                    ),
                ),
            ),
        ),
    )

fun EndOfDayPending.toDocument(baseName: String) =
    ReportDocument(baseName, listOf(foliosSheet(folios), shiftsSheet(cashierShifts), fnrhSheet(fnrhSubmissions)))

private fun foliosSheet(folios: List<PendingFolio>) =
    ReportSheet(
        "Contas",
        ReportTable(
            listOf(
                column("folioId", "Conta", TEXT),
                column("folioNumber", "Número da conta", TEXT),
                column("reservationId", "Reserva", TEXT),
                column("reservationCode", "Código da reserva", TEXT),
                column("departure", "Saída", DATE),
                column("balance", "Saldo", DECIMAL),
            ),
            folios.map {
                listOf(
                    it.folioId.toString(),
                    it.folioNumber,
                    it.reservationId.toString(),
                    it.reservationCode,
                    it.departure,
                    it.balance.amount,
                )
            },
        ),
    )

private fun shiftsSheet(shifts: List<PendingCashierShift>) =
    ReportSheet(
        "Caixas abertos",
        ReportTable(
            listOf(
                column("shiftId", "Turno", TEXT),
                column("shiftNumber", "Número do turno", TEXT),
                column("cashPointName", "Ponto de caixa", TEXT),
                column("operatorId", "Operador", TEXT),
                column("openedOn", "Data operacional", DATE),
                column("openedAt", "Aberto em", INSTANT),
            ),
            shifts.map {
                listOf(
                    it.shiftId.toString(),
                    it.shiftNumber,
                    it.cashPointName,
                    it.operatorId.toString(),
                    it.openedOn,
                    it.openedAt,
                )
            },
        ),
    )

private fun fnrhSheet(submissions: List<PendingFnrhSubmission>) =
    ReportSheet(
        "FNRH pendente",
        ReportTable(
            listOf(
                column("operation", "Operação", TEXT),
                column("status", "Status", TEXT),
                column("reservationId", "Reserva", TEXT),
                column("reservationCode", "Código da reserva", TEXT),
                column("attempts", "Tentativas", INTEGER),
                column("lastFailure", "Última falha", TEXT),
                column("createdAt", "Criada em", INSTANT),
            ),
            submissions.map {
                listOf(
                    it.operation,
                    it.status,
                    it.reservationId.toString(),
                    it.reservationCode,
                    it.attempts,
                    it.lastFailure,
                    it.createdAt,
                )
            },
        ),
    )
