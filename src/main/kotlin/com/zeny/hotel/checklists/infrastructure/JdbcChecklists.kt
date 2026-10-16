package com.zeny.hotel.checklists.infrastructure

import com.zeny.hotel.checklists.ApplicabilityRule
import com.zeny.hotel.checklists.ChecklistContent
import com.zeny.hotel.checklists.ChecklistContext
import com.zeny.hotel.checklists.ChecklistItem
import com.zeny.hotel.checklists.ChecklistItemType
import com.zeny.hotel.checklists.ChecklistSection
import com.zeny.hotel.checklists.ChecklistTemplateId
import com.zeny.hotel.checklists.ChecklistVersionId
import com.zeny.hotel.checklists.ChoiceOption
import com.zeny.hotel.checklists.ConsumeStockAction
import com.zeny.hotel.checklists.ItemAction
import com.zeny.hotel.checklists.ItemActionType
import com.zeny.hotel.checklists.ItemCondition
import com.zeny.hotel.checklists.LinenExchangeAction
import com.zeny.hotel.checklists.LinenMovement
import com.zeny.hotel.checklists.OccurrenceKind
import com.zeny.hotel.checklists.OpenOccurrenceAction
import com.zeny.hotel.checklists.application.ChecklistDirectory
import com.zeny.hotel.checklists.application.ChecklistTemplateStore
import com.zeny.hotel.checklists.application.ChecklistTemplateSummary
import com.zeny.hotel.checklists.application.ChecklistVersionSummary
import com.zeny.hotel.checklists.domain.ChecklistTemplate
import com.zeny.hotel.checklists.domain.ChecklistVersion
import com.zeny.hotel.checklists.domain.ResolutionCandidate
import com.zeny.hotel.shared.AppendOnlyTables
import com.zeny.hotel.shared.UserId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Persistência dos modelos e versões pelo `JdbcClient` (ARCHITECTURE §11), sempre parametrizada. O conteúdo de
 * um modelo vai em `jsonb` (o rascunho em `checklist_template.draft`, cada versão em `checklist_version.content`),
 * num formato próprio da persistência ([StoredContent]), separado dos tipos da API pública. As versões só são
 * inseridas (o papel da aplicação não tem `UPDATE` nem `DELETE` nelas, e um trigger as recusa).
 */
@Component
@Suppress("TooManyFunctions") // a escrita (store) e as leituras (directory) do mesmo par de tabelas
class JdbcChecklists(
    private val jdbc: JdbcClient,
    private val jsonMapper: JsonMapper,
) : ChecklistTemplateStore,
    ChecklistDirectory {
    override fun find(id: ChecklistTemplateId): ChecklistTemplate? = loadTemplate(id, forUpdate = false)

    override fun findForUpdate(id: ChecklistTemplateId): ChecklistTemplate? = loadTemplate(id, forUpdate = true)

    override fun add(template: ChecklistTemplate) {
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_template
                    (id, name, context, active, draft, has_unpublished_changes, published_version, created_at,
                     updated_at, version)
                VALUES (:id, :name, :context, :active, CAST(:draft AS jsonb), :pending, :publishedVersion,
                        :createdAt, :updatedAt, :version)
                """.trimIndent(),
            ).params(templateParams(template))
            .update()
    }

    override fun update(template: ChecklistTemplate) {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE checklists.checklist_template
                    SET name = :name, active = :active, draft = CAST(:draft AS jsonb),
                        has_unpublished_changes = :pending, published_version = :publishedVersion,
                        updated_at = :updatedAt, version = :version
                    WHERE id = :id
                    """.trimIndent(),
                ).params(templateParams(template))
                .update()
        check(updated == 1) { "Modelo de checklist ${template.id} não encontrado para gravar." }
    }

    override fun addVersion(version: ChecklistVersion) {
        jdbc
            .sql(
                """
                INSERT INTO checklists.checklist_version
                    (id, template_id, number, name, context, content, published_at, published_by)
                VALUES (:id, :templateId, :number, :name, :context, CAST(:content AS jsonb), :publishedAt,
                        :publishedBy)
                """.trimIndent(),
            ).param("id", version.id.value)
            .param("templateId", version.templateId.value)
            .param("number", version.number)
            .param("name", version.name)
            .param("context", version.context.name)
            .param("content", write(version.content))
            .param("publishedAt", utc(version.publishedAt))
            .param("publishedBy", version.publishedBy?.value)
            .update()
    }

    override fun templates(
        context: ChecklistContext?,
        active: Boolean?,
        after: Pair<String, UUID>?,
        limit: Int,
    ): List<ChecklistTemplateSummary> =
        jdbc
            .sql(
                """
                SELECT t.id, t.name, t.context, t.active, t.published_version, v.published_at,
                       t.has_unpublished_changes, t.created_at, t.updated_at, t.version
                FROM checklists.checklist_template t
                LEFT JOIN checklists.checklist_version v
                       ON v.template_id = t.id AND v.number = t.published_version
                WHERE (CAST(:context AS text) IS NULL OR t.context = CAST(:context AS text))
                  AND (CAST(:active AS boolean) IS NULL OR t.active = CAST(:active AS boolean))
                  AND (CAST(:afterName AS text) IS NULL
                       OR (t.name, t.id) > (CAST(:afterName AS text), CAST(:afterId AS uuid)))
                ORDER BY t.name, t.id LIMIT :limit
                """.trimIndent(),
            ).param("context", context?.name)
            .param("active", active)
            .param("afterName", after?.first)
            .param("afterId", after?.second)
            .param("limit", limit)
            .query { rs, _ ->
                ChecklistTemplateSummary(
                    ChecklistTemplateId(rs.uuid("id")),
                    rs.getString("name"),
                    ChecklistContext.valueOf(rs.getString("context")),
                    rs.getBoolean("active"),
                    rs.intOrNull("published_version"),
                    rs.instantOrNull("published_at"),
                    rs.getBoolean("has_unpublished_changes"),
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.list()

    override fun versions(
        templateId: ChecklistTemplateId,
        before: Int?,
        limit: Int,
    ): List<ChecklistVersionSummary> =
        jdbc
            .sql(
                """
                SELECT id, number, name, published_at, published_by FROM checklists.checklist_version
                WHERE template_id = :templateId AND (CAST(:before AS integer) IS NULL OR number < CAST(:before AS integer))
                ORDER BY number DESC LIMIT :limit
                """.trimIndent(),
            ).param("templateId", templateId.value)
            .param("before", before)
            .param("limit", limit)
            .query { rs, _ ->
                ChecklistVersionSummary(
                    ChecklistVersionId(rs.uuid("id")),
                    rs.getInt("number"),
                    rs.getString("name"),
                    rs.instant("published_at"),
                    rs.uuidOrNull("published_by")?.let(::UserId),
                )
            }.list()

    override fun version(
        templateId: ChecklistTemplateId,
        number: Int,
    ): ChecklistVersion? =
        jdbc
            .sql("SELECT * FROM checklists.checklist_version WHERE template_id = :templateId AND number = :number")
            .param("templateId", templateId.value)
            .param("number", number)
            .query { rs, _ -> versionOf(rs) }
            .optional()
            .orElse(null)

    override fun version(id: ChecklistVersionId): ChecklistVersion? =
        jdbc
            .sql("SELECT * FROM checklists.checklist_version WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ -> versionOf(rs) }
            .optional()
            .orElse(null)

    override fun resolutionCandidates(context: ChecklistContext): List<ResolutionCandidate> =
        jdbc
            .sql(
                """
                SELECT t.id, t.created_at, t.published_version, v.content -> 'applicability' AS applicability
                FROM checklists.checklist_template t
                JOIN checklists.checklist_version v ON v.template_id = t.id AND v.number = t.published_version
                WHERE t.context = :context AND t.active
                ORDER BY t.created_at, t.id
                """.trimIndent(),
            ).param("context", context.name)
            .query { rs, _ ->
                ResolutionCandidate(
                    ChecklistTemplateId(rs.uuid("id")),
                    rs.instant("created_at"),
                    rs.getInt("published_version"),
                    jsonMapper.readValue<List<StoredRule>>(rs.getString("applicability")).map(StoredRule::toRule),
                )
            }.list()

    private fun loadTemplate(
        id: ChecklistTemplateId,
        forUpdate: Boolean,
    ): ChecklistTemplate? {
        // A trava fica só na linha do modelo, num comando à parte: com o `LEFT JOIN` da versão no mesmo
        // `SELECT … FOR UPDATE`, quem esperou a trava reavaliaria só a linha do modelo (a versão publicada por
        // quem segurava a trava ficaria de fora). A leitura seguinte, noutro comando, já vê o que foi confirmado.
        if (forUpdate) {
            val locked =
                jdbc
                    .sql("SELECT id FROM checklists.checklist_template WHERE id = :id FOR UPDATE")
                    .param("id", id.value)
                    .query(UUID::class.java)
                    .optional()
            if (locked.isEmpty) return null
        }
        return jdbc
            .sql(
                """
                SELECT t.*, v.content AS published_content, v.published_at
                FROM checklists.checklist_template t
                LEFT JOIN checklists.checklist_version v
                       ON v.template_id = t.id AND v.number = t.published_version
                WHERE t.id = :id
                """.trimIndent(),
            ).param("id", id.value)
            .query { rs, _ ->
                val published =
                    rs.intOrNull("published_version")?.let { number ->
                        ChecklistTemplate.PublishedState(
                            number,
                            read(rs.getString("published_content")),
                            rs.instant("published_at"),
                        )
                    }
                ChecklistTemplate.restore(
                    ChecklistTemplateId(rs.uuid("id")),
                    rs.getString("name"),
                    ChecklistContext.valueOf(rs.getString("context")),
                    rs.getBoolean("active"),
                    read(rs.getString("draft")),
                    published,
                    rs.instant("created_at"),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.optional()
            .orElse(null)
    }

    private fun versionOf(rs: ResultSet) =
        ChecklistVersion(
            ChecklistVersionId(rs.uuid("id")),
            ChecklistTemplateId(rs.uuid("template_id")),
            rs.getInt("number"),
            rs.getString("name"),
            ChecklistContext.valueOf(rs.getString("context")),
            read(rs.getString("content")),
            rs.instant("published_at"),
            rs.uuidOrNull("published_by")?.let(::UserId),
        )

    private fun templateParams(template: ChecklistTemplate): Map<String, Any?> =
        mapOf(
            "id" to template.id.value,
            "name" to template.name,
            "context" to template.context.name,
            "active" to template.active,
            "draft" to write(template.draft),
            "pending" to template.hasUnpublishedChanges,
            "publishedVersion" to template.publishedVersion,
            "createdAt" to utc(template.createdAt),
            "updatedAt" to utc(template.updatedAt),
            "version" to template.version,
        )

    private fun write(content: ChecklistContent): String = jsonMapper.writeValueAsString(StoredContent.of(content))

    private fun read(json: String): ChecklistContent = jsonMapper.readValue<StoredContent>(json).toContent()
}

/** As versões publicadas são *append-only* para o papel da aplicação; a partida confere (DatabaseRoleGuard). */
@Configuration(proxyBeanMethods = false)
class ChecklistTablesConfiguration {
    @Bean
    fun checklistsAppendOnlyTables(): AppendOnlyTables = AppendOnlyTables { setOf(VERSION_TABLE) }

    companion object {
        const val VERSION_TABLE = "checklists.checklist_version"
    }
}

// --- Formato do conteúdo no jsonb --------------------------------------------------------------------------
// Separado dos tipos da API pública: renomear uma propriedade lá não muda o que já está gravado. Os conjuntos
// vão ordenados, para o mesmo conteúdo gerar sempre o mesmo JSON.

private data class StoredContent(
    val applicability: List<StoredRule>,
    val sections: List<StoredSection>,
) {
    fun toContent() = ChecklistContent(applicability.map(StoredRule::toRule), sections.map(StoredSection::toSection))

    companion object {
        fun of(content: ChecklistContent) =
            StoredContent(content.applicability.map(StoredRule::of), content.sections.map(StoredSection::of))
    }
}

private data class StoredRule(
    val roomTypeIds: List<UUID>,
    val cleaningTypeIds: List<UUID>,
    val areaIds: List<UUID>,
    val assetIds: List<UUID>,
) {
    fun toRule() = ApplicabilityRule(roomTypeIds.toSet(), cleaningTypeIds.toSet(), areaIds.toSet(), assetIds.toSet())

    companion object {
        fun of(rule: ApplicabilityRule) =
            StoredRule(
                rule.roomTypeIds.sorted(),
                rule.cleaningTypeIds.sorted(),
                rule.areaIds.sorted(),
                rule.assetIds.sorted(),
            )
    }
}

private data class StoredSection(
    val title: String,
    val items: List<StoredItem>,
) {
    fun toSection() = ChecklistSection(title, items.map(StoredItem::toItem))

    companion object {
        fun of(section: ChecklistSection) = StoredSection(section.title, section.items.map(StoredItem::of))
    }
}

private data class StoredItem(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val visibleWhen: StoredCondition?,
    val options: List<StoredOption>,
    val unit: String?,
    val actions: List<StoredAction>,
) {
    fun toItem() =
        ChecklistItem(
            key,
            label,
            ChecklistItemType.valueOf(type),
            required,
            visibleWhen?.let { ItemCondition(it.itemKey, it.answer) },
            options.map { ChoiceOption(it.value, it.label) },
            unit,
            actions.map(StoredAction::toAction),
        )

    companion object {
        fun of(item: ChecklistItem) =
            StoredItem(
                item.key,
                item.label,
                item.type.name,
                item.required,
                item.visibleWhen?.let { StoredCondition(it.itemKey, it.answer) },
                item.options.map { StoredOption(it.value, it.label) },
                item.unit,
                item.actions.map(StoredAction::of),
            )
    }
}

private data class StoredCondition(
    val itemKey: String,
    val answer: String,
)

private data class StoredOption(
    val value: String,
    val label: String,
)

private data class StoredAction(
    val type: String,
    val stockItemId: UUID? = null,
    val defaultQuantity: BigDecimal? = null,
    val linenTypeId: UUID? = null,
    val movement: String? = null,
    val occurrenceKind: String? = null,
    val category: String? = null,
) {
    fun toAction(): ItemAction =
        when (ItemActionType.valueOf(type)) {
            ItemActionType.CONSUME_STOCK -> {
                ConsumeStockAction(checkNotNull(stockItemId), defaultQuantity)
            }

            ItemActionType.LINEN_EXCHANGE -> {
                LinenExchangeAction(
                    checkNotNull(linenTypeId),
                    LinenMovement.valueOf(checkNotNull(movement)),
                )
            }

            ItemActionType.OPEN_OCCURRENCE -> {
                OpenOccurrenceAction(
                    OccurrenceKind.valueOf(checkNotNull(occurrenceKind)),
                    category,
                )
            }
        }

    companion object {
        fun of(action: ItemAction) =
            when (action) {
                is ConsumeStockAction -> {
                    StoredAction(
                        action.type.name,
                        stockItemId = action.stockItemId,
                        defaultQuantity = action.defaultQuantity,
                    )
                }

                is LinenExchangeAction -> {
                    StoredAction(action.type.name, linenTypeId = action.linenTypeId, movement = action.movement.name)
                }

                is OpenOccurrenceAction -> {
                    StoredAction(action.type.name, occurrenceKind = action.kind.name, category = action.category)
                }
            }
    }
}

private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

private fun ResultSet.instantOrNull(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
