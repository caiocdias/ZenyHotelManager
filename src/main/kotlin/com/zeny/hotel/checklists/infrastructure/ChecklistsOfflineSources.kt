package com.zeny.hotel.checklists.infrastructure

import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistExecutionStatus
import com.zeny.hotel.checklists.ChecklistsOffline
import com.zeny.hotel.shared.OfflineChange
import com.zeny.hotel.shared.OfflineChangeRequest
import com.zeny.hotel.shared.OfflineChangeSource
import com.zeny.hotel.shared.OfflinePosition
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Os modelos como fonte do download offline ([ChecklistsOffline.TEMPLATES]): cada modelo ativo e publicado, com o
 * conteúdo da última versão publicada (o que o app executa e com que escolhe o modelo de um sujeito). Desativado,
 * ou nunca publicado, vai como *tombstone*. A posição é do trigger `checklist_template_offline_change` (publicar,
 * desativar e reativar mudam a linha; editar o rascunho também, e o modelo só é reenviado igual). Os modelos não
 * têm dono: o escopo não filtra nada.
 */
@Component
class ChecklistTemplatesOfflineSource(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : OfflineChangeSource {
    override val source = ChecklistsOffline.TEMPLATES

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> =
        jdbc
            .sql(
                """
                SELECT t.id, t.name, t.context, t.active, t.version, v.id AS version_id, v.number, v.content,
                       ${OfflineChangeRequest.positionColumns("t")}
                FROM checklists.checklist_template t
                LEFT JOIN checklists.checklist_version v ON v.template_id = t.id AND v.number = t.published_version
                WHERE ${OfflineChangeRequest.window("t")}
                ORDER BY t.change_xid, t.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .query { rs, _ ->
                val position = OfflinePosition.from(rs)
                val id = rs.getObject("id", UUID::class.java)
                val version = rs.getLong("version")
                val versionId = rs.getObject("version_id", UUID::class.java)
                if (!rs.getBoolean("active") || versionId == null) {
                    OfflineChange.tombstone(position, id, version)
                } else {
                    OfflineChange.upsert(
                        position,
                        id,
                        version,
                        OfflineTemplate(
                            name = rs.getString("name"),
                            context = ChecklistContext.valueOf(rs.getString("context")),
                            versionId = versionId,
                            versionNumber = rs.getInt("number"),
                            content = jsonMapper.readTree(rs.getString("content")),
                        ),
                    )
                }
            }.list()

    /** O que o app recebe de cada modelo: a última versão publicada (aplicabilidade, seções, itens e ações). */
    data class OfflineTemplate(
        val name: String,
        val context: ChecklistContext,
        val versionId: UUID,
        val versionNumber: Int,
        val content: JsonNode,
    )
}

/**
 * As execuções como fonte do download offline ([ChecklistsOffline.EXECUTIONS]): as pendentes e em andamento, com
 * `OWN` só as de que o usuário é candidato ou executor (H14); concluída vai como *tombstone* (sai do app). Sem as
 * respostas nem dado de hóspede (RN-13): só o que o app precisa para listar e iniciar.
 */
@Component
class ChecklistExecutionsOfflineSource(
    private val jdbc: JdbcClient,
) : OfflineChangeSource {
    override val source = ChecklistsOffline.EXECUTIONS

    override fun changes(request: OfflineChangeRequest): List<OfflineChange> =
        jdbc
            .sql(
                """
                SELECT e.id, e.status, e.template_id, e.version_id, e.version_number, e.context, e.subject_type,
                       e.subject_id, e.routine_id, e.business_date, e.due_at, e.duplicate_of, e.executor_id,
                       e.started_at, e.version, ${OfflineChangeRequest.positionColumns("e")}
                FROM checklists.checklist_execution e
                WHERE ${OfflineChangeRequest.window("e")}
                  AND (CAST(:owner AS uuid) IS NULL OR e.executor_id = CAST(:owner AS uuid)
                       OR CAST(:owner AS uuid) = ANY (e.candidate_ids))
                ORDER BY e.change_xid, e.change_seq
                LIMIT :limit
                """.trimIndent(),
            ).params(request.sqlParams())
            .query { rs, _ ->
                val position = OfflinePosition.from(rs)
                val id = rs.getObject("id", UUID::class.java)
                val version = rs.getLong("version")
                val status = ChecklistExecutionStatus.valueOf(rs.getString("status"))
                if (status == ChecklistExecutionStatus.COMPLETED) {
                    OfflineChange.tombstone(position, id, version)
                } else {
                    OfflineChange.upsert(
                        position,
                        id,
                        version,
                        OfflineExecution(
                            status = status,
                            templateId = rs.getObject("template_id", UUID::class.java),
                            versionId = rs.getObject("version_id", UUID::class.java),
                            versionNumber = rs.getInt("version_number").takeUnless { rs.wasNull() },
                            context = ChecklistContext.valueOf(rs.getString("context")),
                            subjectType = rs.getString("subject_type"),
                            subjectId = rs.getObject("subject_id", UUID::class.java),
                            routineId = rs.getObject("routine_id", UUID::class.java),
                            businessDate = rs.getObject("business_date", LocalDate::class.java),
                            dueAt = rs.getObject("due_at", OffsetDateTime::class.java)?.toInstant(),
                            duplicateOf = rs.getObject("duplicate_of", UUID::class.java),
                            executorId = rs.getObject("executor_id", UUID::class.java),
                            startedAt = rs.getObject("started_at", OffsetDateTime::class.java)?.toInstant(),
                        ),
                    )
                }
            }.list()

    /** O que o app recebe de cada execução: situação, modelo e versão, sujeito, rotina e horários; sem respostas. */
    data class OfflineExecution(
        val status: ChecklistExecutionStatus,
        val templateId: UUID,
        val versionId: UUID?,
        val versionNumber: Int?,
        val context: ChecklistContext,
        val subjectType: String?,
        val subjectId: UUID?,
        val routineId: UUID?,
        val businessDate: LocalDate?,
        val dueAt: Instant?,
        val duplicateOf: UUID?,
        val executorId: UUID?,
        val startedAt: Instant?,
    )
}
