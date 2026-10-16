package com.zeny.hotel.checklists

import com.zeny.hotel.checklists.ChecklistItemType.CHECK
import com.zeny.hotel.checklists.ChecklistItemType.MULTI_CHOICE
import com.zeny.hotel.checklists.ChecklistItemType.NUMBER
import com.zeny.hotel.checklists.ChecklistItemType.PHOTO
import com.zeny.hotel.checklists.ChecklistItemType.QUANTITY
import com.zeny.hotel.checklists.ChecklistItemType.SINGLE_CHOICE
import com.zeny.hotel.checklists.ChecklistItemType.TEXT
import com.zeny.hotel.checklists.domain.ChecklistTemplate
import com.zeny.hotel.checklists.domain.ResolutionCandidate
import com.zeny.hotel.checklists.domain.TemplateResolution
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Modelos de checklist no domínio (M2 E06, RF-CHK-01 a 06), sem Spring: rascunho, publicação que congela a
 * versão (editar o publicado só muda o rascunho, e a próxima publicação é a versão seguinte), os sete tipos de
 * item, condições só para item anterior com resposta compatível, ações só nos tipos que as disparam e a escolha
 * do modelo pela aplicabilidade.
 */
class ChecklistDomainTest {
    private val t0 = Instant.parse("2026-10-16T12:00:00Z")
    private val t1 = Instant.parse("2026-10-16T13:00:00Z")
    private val t2 = Instant.parse("2026-10-16T14:00:00Z")
    private val user = UserId(UuidV7.generate())

    // --- Versões -------------------------------------------------------------------------------------

    @Test
    fun `publishing freezes version 1, editing only changes the draft, and the next publish is version 2`() {
        val template = ChecklistTemplate.create("  Limpeza de saída  ", ChecklistContext.HOUSEKEEPING, content(), t0)
        assertSoftly {
            template.name shouldBe "Limpeza de saída"
            template.publishedVersion.shouldBeNull()
            template.hasUnpublishedChanges shouldBe true
            template.version shouldBe 0
        }

        val v1 = template.publish(t1, user)
        assertSoftly {
            v1.number shouldBe 1
            v1.templateId shouldBe template.id
            v1.name shouldBe "Limpeza de saída"
            v1.publishedAt shouldBe t1
            v1.publishedBy shouldBe user
            v1.content shouldBe template.draft
            template.publishedVersion shouldBe 1
            template.hasUnpublishedChanges shouldBe false
            template.version shouldBe 1
        }
        val frozen = v1.copy()

        // Publicar de novo sem mudança: nada a publicar.
        shouldThrow<BusinessRuleException> { template.publish(t1, user) }.code shouldBe
            ChecklistsErrorCodes.CHECKLIST_NOTHING_TO_PUBLISH

        val edited = content(extraItem = item("minibar", "Minibar conferido"))
        template.edit("Limpeza de saída", edited, t2) shouldBe true
        assertSoftly {
            template.hasUnpublishedChanges shouldBe true
            template.publishedVersion shouldBe 1
            template.publishedContent shouldBe v1.content
            v1 shouldBe frozen
        }

        val v2 = template.publish(t2, user)
        assertSoftly {
            v2.number shouldBe 2
            (v2.id == v1.id) shouldBe false
            v2.content.items.map { it.key } shouldContainExactly listOf("bed", "towels", "minibar")
            v1 shouldBe frozen
            v1.content.items.map { it.key } shouldContainExactly listOf("bed", "towels")
            template.publishedVersion shouldBe 2
        }
    }

    @Test
    fun `editing back to the published content leaves nothing to publish, and no change keeps the record version`() {
        val template = ChecklistTemplate.create("Inspeção", ChecklistContext.INSPECTION, content(), t0)
        template.publish(t1, user)
        val version = template.version

        template.edit("Inspeção", content(), t2) shouldBe false
        template.version shouldBe version

        template.edit("Inspeção", content(extraItem = item("tv", "TV funciona")), t2) shouldBe true
        template.edit("Inspeção", content(), t2) shouldBe true
        template.hasUnpublishedChanges shouldBe false
        shouldThrow<BusinessRuleException> { template.publish(t2, user) }.code shouldBe
            ChecklistsErrorCodes.CHECKLIST_NOTHING_TO_PUBLISH

        // Só o nome muda: vale na hora e não pede publicação.
        template.edit("Inspeção da supervisora", content(), t2) shouldBe true
        template.name shouldBe "Inspeção da supervisora"
        template.hasUnpublishedChanges shouldBe false
    }

    @Test
    fun `a deactivated template is not edited nor published until reactivated`() {
        val template = ChecklistTemplate.create("Piscina", ChecklistContext.COMMON_AREA, content(), t0)
        template.deactivate(t1) shouldBe true
        template.deactivate(t1) shouldBe false
        shouldThrow<BusinessRuleException> { template.publish(t1, user) }.code shouldBe
            ChecklistsErrorCodes.CHECKLIST_TEMPLATE_INACTIVE
        shouldThrow<BusinessRuleException> { template.edit("Piscina", content(), t1) }.code shouldBe
            ChecklistsErrorCodes.CHECKLIST_TEMPLATE_INACTIVE
        template.reactivate(t2) shouldBe true
        template.publish(t2, user).number shouldBe 1
    }

    // --- Itens ---------------------------------------------------------------------------------------

    @Test
    fun `the seven item types of SPEC 8_2_11 are accepted, and nothing is required unless the template says so`() {
        val items =
            listOf(
                ChecklistItem("ok", "Cama feita", CHECK),
                ChecklistItem("qty", "Toalhas repostas", QUANTITY, unit = " un "),
                ChecklistItem("temp", "Temperatura da câmara", NUMBER, required = true, unit = "°C"),
                ChecklistItem("note", "Observações", TEXT),
                ChecklistItem("smell", "Cheiro", SINGLE_CHOICE, options = options("bom", "ruim")),
                ChecklistItem("issues", "Problemas", MULTI_CHOICE, options = options("tv", "ar", "chuveiro")),
                ChecklistItem("photo", "Foto do banheiro", PHOTO),
            )
        val template = ChecklistTemplate.create("Tudo", ChecklistContext.OTHER, single(items), t0)

        template.draft.items.map { it.type } shouldContainExactly ChecklistItemType.entries
        template.draft.items
            .single { it.type == PHOTO }
            .required shouldBe false
        template.draft.items
            .filter { it.required }
            .map { it.key } shouldContainExactly listOf("temp")
        template.draft.items
            .single { it.key == "qty" }
            .unit shouldBe "un"
    }

    @Test
    fun `item keys are unique, options only on choice items and units only on quantity and number`() {
        rejects(ChecklistsErrorCodes.CHECKLIST_ITEM_KEY_DUPLICATED, "sections[0].items[1].key") {
            single(listOf(item("a", "A"), item("a", "B")))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID, "sections[0].items[0].options") {
            single(listOf(ChecklistItem("c", "Escolha", SINGLE_CHOICE, options = options("so-uma"))))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID, "sections[0].items[0].options") {
            single(listOf(ChecklistItem("c", "Escolha", MULTI_CHOICE, options = options("x", "x"))))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_ITEM_OPTIONS_INVALID, "sections[0].items[0].options") {
            single(listOf(ChecklistItem("c", "Marcação", CHECK, options = options("x", "y"))))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_DATA_INVALID, "sections[0].items[0].unit") {
            single(listOf(ChecklistItem("t", "Texto", TEXT, unit = "kg")))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_DATA_INVALID, "sections[0].items[0].key") {
            single(listOf(item("Com Espaço", "A")))
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_DATA_INVALID, "sections") { ChecklistContent(emptyList(), emptyList()) }
        rejects(ChecklistsErrorCodes.CHECKLIST_DATA_INVALID, "sections[0].items") { single(emptyList()) }
        rejects(ChecklistsErrorCodes.CHECKLIST_DATA_INVALID, "sections[0].title") {
            ChecklistContent(emptyList(), listOf(ChecklistSection("  ", listOf(item("a", "A")))))
        }
    }

    // --- Condições -----------------------------------------------------------------------------------

    @Test
    fun `a condition cites an earlier item with a compatible answer`() {
        val choice = ChecklistItem("smell", "Cheiro", SINGLE_CHOICE, options = options("bom", "ruim"))
        val multi = ChecklistItem("issues", "Problemas", MULTI_CHOICE, options = options("tv", "ar"))
        val accepted =
            ChecklistContent(
                emptyList(),
                listOf(
                    ChecklistSection("Quarto", listOf(item("bed", "Cama"), choice, multi)),
                    ChecklistSection(
                        "Detalhes",
                        listOf(
                            item("why", "Por que não?", TEXT, ItemCondition("bed", "NOT_OK")),
                            item("vent", "Ventilou?", CHECK, ItemCondition("smell", "ruim")),
                            item("tv-note", "Qual canal?", TEXT, ItemCondition("issues", " tv ")),
                            // Condição sobre um item que também é condicional.
                            item("vent-note", "Por quanto tempo?", TEXT, ItemCondition("vent", "OK")),
                        ),
                    ),
                ),
            )
        val template = ChecklistTemplate.create("Condicionais", ChecklistContext.HOUSEKEEPING, accepted, t0)
        template.draft.items
            .single { it.key == "tv-note" }
            .visibleWhen shouldBe ItemCondition("issues", "tv")
    }

    @Test
    fun `a condition on a later item, on itself or on an unknown item is refused with a stable code`() {
        rejects(
            ChecklistsErrorCodes.CHECKLIST_CONDITION_FORWARD_REFERENCE,
            "sections[0].items[0].visibleWhen.itemKey",
        ) {
            single(listOf(item("a", "A", CHECK, ItemCondition("b", "OK")), item("b", "B")))
        }
        rejects(
            ChecklistsErrorCodes.CHECKLIST_CONDITION_FORWARD_REFERENCE,
            "sections[0].items[0].visibleWhen.itemKey",
        ) {
            single(listOf(item("a", "A", CHECK, ItemCondition("a", "OK"))))
        }
        // Item posterior em outra seção.
        rejects(
            ChecklistsErrorCodes.CHECKLIST_CONDITION_FORWARD_REFERENCE,
            "sections[0].items[0].visibleWhen.itemKey",
        ) {
            ChecklistContent(
                emptyList(),
                listOf(
                    ChecklistSection("1", listOf(item("a", "A", CHECK, ItemCondition("z", "OK")))),
                    ChecklistSection("2", listOf(item("z", "Z"))),
                ),
            )
        }
        rejects(ChecklistsErrorCodes.CHECKLIST_CONDITION_UNKNOWN_ITEM, "sections[0].items[1].visibleWhen.itemKey") {
            single(listOf(item("a", "A"), item("b", "B", CHECK, ItemCondition("nope", "OK"))))
        }
    }

    @Test
    fun `a condition with an answer the cited item cannot have is refused with a stable code`() {
        val code = ChecklistsErrorCodes.CHECKLIST_CONDITION_INCOMPATIBLE
        val path = "sections[0].items[1].visibleWhen.answer"
        rejects(code, path) { single(listOf(item("a", "A"), item("b", "B", CHECK, ItemCondition("a", "YES")))) }
        rejects(code, path) {
            single(
                listOf(
                    ChecklistItem("c", "Cheiro", SINGLE_CHOICE, options = options("bom", "ruim")),
                    item("b", "B", CHECK, ItemCondition("c", "pessimo")),
                ),
            )
        }
        // Itens sem resposta discreta não servem de condição.
        listOf(QUANTITY, NUMBER, TEXT, PHOTO).forEach { type ->
            rejects(code, path) {
                single(listOf(ChecklistItem("x", "X", type), item("b", "B", CHECK, ItemCondition("x", "1"))))
            }
        }
    }

    // --- Ações ---------------------------------------------------------------------------------------

    @Test
    fun `stock and linen actions only on quantity items, and occurrences only on check items`() {
        val stock = ConsumeStockAction(UUID.randomUUID(), BigDecimal("2.500"))
        val linen = LinenExchangeAction(UUID.randomUUID(), LinenMovement.REMOVED)
        val occurrence = OpenOccurrenceAction(OccurrenceKind.MAINTENANCE, " plumbing ")
        val template =
            ChecklistTemplate.create(
                "Ações",
                ChecklistContext.HOUSEKEEPING,
                single(
                    listOf(
                        ChecklistItem("soap", "Sabonetes", QUANTITY, actions = listOf(stock, linen)),
                        ChecklistItem("shower", "Chuveiro", CHECK, actions = listOf(occurrence)),
                    ),
                ),
                t0,
            )
        template.draft.items[0].actions shouldContainExactly
            listOf(stock.copy(defaultQuantity = BigDecimal("2.5")), linen)
        template.draft.items[1].actions shouldContainExactly listOf(occurrence.copy(category = "PLUMBING"))

        val mismatch = ChecklistsErrorCodes.CHECKLIST_ACTION_ITEM_TYPE_MISMATCH
        ChecklistItemType.entries.filter { it != QUANTITY }.forEach { type ->
            rejects(mismatch, "sections[0].items[0].actions[0].type") { single(listOf(itemWith(type, stock))) }
            rejects(mismatch, "sections[0].items[0].actions[0].type") { single(listOf(itemWith(type, linen))) }
        }
        ChecklistItemType.entries.filter { it != CHECK }.forEach { type ->
            rejects(mismatch, "sections[0].items[0].actions[0].type") { single(listOf(itemWith(type, occurrence))) }
        }

        val invalid = ChecklistsErrorCodes.CHECKLIST_ACTION_INVALID
        rejects(invalid, "sections[0].items[0].actions[0].defaultQuantity") {
            single(listOf(itemWith(QUANTITY, stock.copy(defaultQuantity = BigDecimal.ZERO))))
        }
        rejects(invalid, "sections[0].items[0].actions[0].defaultQuantity") {
            single(listOf(itemWith(QUANTITY, stock.copy(defaultQuantity = BigDecimal("1.0001")))))
        }
        rejects(invalid, "sections[0].items[0].actions") {
            single(
                listOf(ChecklistItem("q", "Q", QUANTITY, actions = listOf(stock, stock.copy(defaultQuantity = null)))),
            )
        }
        rejects(invalid, "sections[0].items[0].actions[0].category") {
            single(listOf(itemWith(CHECK, occurrence.copy(category = "com espaço"))))
        }
    }

    // --- Aplicabilidade ------------------------------------------------------------------------------

    @Test
    fun `an applicability rule needs at least one criterion`() {
        rejects(ChecklistsErrorCodes.CHECKLIST_APPLICABILITY_INVALID, "applicability[1]") {
            content(
                applicability = listOf(ApplicabilityRule(roomTypeIds = setOf(UUID.randomUUID())), ApplicabilityRule()),
            )
        }
    }

    @Test
    fun `the most specific matching template wins, and without a matching rule the general one`() {
        val suite = UUID.randomUUID()
        val standard = UUID.randomUUID()
        val departure = UUID.randomUUID()
        val pool = UUID.randomUUID()
        val general = candidate(emptyList(), t0)
        val suites = candidate(listOf(ApplicabilityRule(roomTypeIds = setOf(suite))), t1)
        val suiteDeparture =
            candidate(listOf(ApplicabilityRule(roomTypeIds = setOf(suite), cleaningTypeIds = setOf(departure))), t2)
        val poolOrStandard =
            candidate(
                listOf(ApplicabilityRule(areaIds = setOf(pool)), ApplicabilityRule(roomTypeIds = setOf(standard))),
                t2,
            )
        val all = listOf(general, suites, suiteDeparture, poolOrStandard)

        assertSoftly {
            TemplateResolution.choose(all, SubjectAttributes(roomTypeId = suite)) shouldBe suites
            TemplateResolution.choose(all, SubjectAttributes(roomTypeId = suite, cleaningTypeId = departure)) shouldBe
                suiteDeparture
            TemplateResolution.choose(all, SubjectAttributes(roomTypeId = standard)) shouldBe poolOrStandard
            TemplateResolution.choose(all, SubjectAttributes(areaId = pool)) shouldBe poolOrStandard
            // Nenhuma regra casa (ou o sujeito não tem o atributo): o geral.
            TemplateResolution.choose(all, SubjectAttributes(roomTypeId = UUID.randomUUID())) shouldBe general
            TemplateResolution.choose(all, SubjectAttributes(cleaningTypeId = departure)) shouldBe general
            TemplateResolution.choose(all, SubjectAttributes()) shouldBe general
            // Sem modelo geral e sem regra que case: nenhum.
            TemplateResolution.choose(listOf(suites), SubjectAttributes()).shouldBeNull()
        }
    }

    @Test
    fun `a tie between equally specific templates goes to the one created first`() {
        val suite = UUID.randomUUID()
        val older = candidate(listOf(ApplicabilityRule(roomTypeIds = setOf(suite))), t0)
        val newer = candidate(listOf(ApplicabilityRule(roomTypeIds = setOf(suite, UUID.randomUUID()))), t1)
        val olderGeneral = candidate(emptyList(), t0)
        val newerGeneral = candidate(emptyList(), t1)

        TemplateResolution.choose(listOf(newer, older), SubjectAttributes(roomTypeId = suite)) shouldBe older
        TemplateResolution.choose(listOf(newerGeneral, olderGeneral), SubjectAttributes()) shouldBe olderGeneral
    }

    // --- Apoio ---------------------------------------------------------------------------------------

    private fun candidate(
        rules: List<ApplicabilityRule>,
        createdAt: Instant,
    ) = ResolutionCandidate(ChecklistTemplateId.new(), createdAt, 1, rules)

    private fun rejects(
        code: ErrorCode,
        field: String,
        content: () -> ChecklistContent,
    ) {
        val error =
            shouldThrow<InvalidInputException> { ChecklistTemplate.create("X", ChecklistContext.OTHER, content(), t0) }
        assertSoftly {
            error.code shouldBe code
            error.violations.map { it.field } shouldContainExactly listOf(field)
            error.violations.map { it.code } shouldContainExactly listOf(code.value)
        }
    }

    private fun item(
        key: String,
        label: String,
        type: ChecklistItemType = CHECK,
        visibleWhen: ItemCondition? = null,
    ) = ChecklistItem(key, label, type, visibleWhen = visibleWhen)

    private fun itemWith(
        type: ChecklistItemType,
        action: ItemAction,
    ) = ChecklistItem(
        "x",
        "X",
        type,
        options = if (type == SINGLE_CHOICE || type == MULTI_CHOICE) options("a", "b") else emptyList(),
        actions = listOf(action),
    )

    private fun options(vararg values: String) = values.map { ChoiceOption(it, it.uppercase()) }

    private fun single(items: List<ChecklistItem>) =
        ChecklistContent(emptyList(), listOf(ChecklistSection("Seção", items)))

    private fun content(
        extraItem: ChecklistItem? = null,
        applicability: List<ApplicabilityRule> = emptyList(),
    ) = ChecklistContent(
        applicability,
        listOf(
            ChecklistSection("Quarto", listOfNotNull(item("bed", "Cama feita"), item("towels", "Toalhas"), extraItem)),
        ),
    )
}
