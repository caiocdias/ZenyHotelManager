package com.zeny.hotel.guests

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.guests.web.GuestPrivacyController
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.shared.BlindIndex
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.asUser
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Os perfis com estadia ativa no teste ([GuestActiveStayCheck] de teste, como `reservations` a implementará). */
object TestActiveStays {
    val blocked: MutableSet<GuestId> = ConcurrentHashMap.newKeySet()
}

/** Um contribuidor de dados pessoais de teste, como um módulo de cima (M4/M5) o publicaria. */
class TestPersonalDataContributor : GuestPersonalDataContributor {
    override val section = "test-example.stays"

    override fun personalData(guestIds: List<GuestId>): Any = mapOf("guestIds" to guestIds.map { it.toString() })
}

@TestConfiguration(proxyBeanMethods = false)
class GuestPrivacyTestConfiguration {
    @Bean
    fun testPersonalDataContributor(): GuestPersonalDataContributor = TestPersonalDataContributor()

    @Bean
    fun testActiveStayCheck(): GuestActiveStayCheck =
        object : GuestActiveStayCheck {
            override fun hasActiveStay(guestIds: List<GuestId>): Boolean =
                guestIds.any { it in TestActiveStays.blocked }
        }
}

/**
 * LGPD no `guests` (M3 E03, RF-HOS-02, 07 e 08, RF-ACS-08, RNF-09, H3, H10 a H12): consentimentos por finalidade
 * (restrição alimentar só com o ativo); anexos sensíveis vinculados pela `FilesApi` e baixados só pela URL do
 * servidor, com emissão e download na trilha; exportação e anonimização como ações sensíveis (aprovação de
 * superior), auditadas com solicitante e aprovador, valendo para a cadeia de perfis mesclados; a anonimização apaga
 * dados pessoais, documentos, índices cegos e anexos e mantém o id e os consentimentos; a view `report_guests` sem
 * dado pessoal, só com `SELECT` para a aplicação e legível por um papel que só a lê.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class, GuestPrivacyTestConfiguration::class)
@Suppress("LargeClass", "LongParameterList") // cenários de ponta a ponta de cada direito do titular
class GuestPrivacyApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val blindIndex: BlindIndex,
    @Autowired private val flyway: Flyway,
    @Autowired private val jsonMapper: JsonMapper,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.PROFILES_READ), ALL)
    private val manage = TestGrant(definition(GuestsPermissions.PROFILES_MANAGE), ALL)
    private val attachmentsRead = TestGrant(definition(GuestsPermissions.ATTACHMENTS_READ), ALL)
    private val attachmentsManage = TestGrant(definition(GuestsPermissions.ATTACHMENTS_MANAGE), ALL)
    private val export = TestGrant(definition(GuestsPermissions.DATA_EXPORT), ALL)
    private val anonymize = TestGrant(definition(GuestsPermissions.DATA_ANONYMIZE), ALL)

    @AfterEach
    fun unblockStays() {
        TestActiveStays.blocked.clear()
    }

    // --- Consentimentos e restrições alimentares -----------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o ciclo inteiro: recusa, consentimento, gravação cifrada, revogação e trilha
    fun `dietary restrictions need the active consent, which is granted and revoked with source and instant`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val editor = createUserWith(read, manage)
            val id = guestId(guests.createGuest())
            val path = "/api/v1/guests/$id"
            val dietary = guests.profileBody(extra = mapOf("dietaryRestrictions" to DIETARY))

            // Sem o consentimento: 422, nada gravado.
            call(PUT, path, asTestUser(editor), dietary, ifMatch = "\"0\"")
                .problem(422, "DIETARY_RESTRICTIONS_REQUIRE_CONSENT")

            // Consentimento: sem a permissão 403; dado, 201; de novo, 200 com o mesmo.
            val consent = mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "IN_PERSON")
            call(POST, "$path/consents", asTestUser(reader), consent).problem(403, "PERMISSION_DENIED")
            val granted = call(POST, "$path/consents", asTestUser(editor), consent).expectStatus(201)!!
            granted["active"].asBoolean() shouldBe true
            granted["grantedSource"].asString() shouldBe "IN_PERSON"
            granted["grantedBy"].asString() shouldBe editor.id.toString()
            granted["grantedAt"].isNull shouldBe false
            granted["revokedAt"].isNull shouldBe true
            val consentId = granted["id"].asString()
            call(POST, "$path/consents", asTestUser(editor), consent).ok()["id"].asString() shouldBe consentId

            // Com ele, a restrição é gravada (cifrada) e volta na leitura; a trilha leva só o nome do campo, e a
            // correção a pedido do titular leva a origem.
            val etag = guests.etagOf(path)
            val updated =
                call(PUT, "$path?dataSubjectRequest=true", asTestUser(editor), dietary, ifMatch = etag).ok()
            updated["dietaryRestrictions"].asString() shouldBe DIETARY
            val stored = column(id, "dietary_restrictions").shouldNotBeNull()
            stored shouldStartWith "v1:"
            stored shouldNotContain "glúten"
            val after = auditAfter(id, "guests.guest.update")
            after["changedFields"].values().map { it.asString() } shouldContain "dietaryRestrictions"
            after["origin"].asString() shouldBe "DATA_SUBJECT_REQUEST"
            after.toString() shouldNotContain "glúten"
            // A gravação dos dados sensíveis da FNRH também registra o pedido do titular.
            call(
                PUT,
                "$path/sensitive-data?dataSubjectRequest=true",
                asTestUser(editor),
                mapOf("raceColor" to "BROWN"),
            ).ok()
            auditAfter(id, "guests.guest.record-sensitive-data")["origin"].asString() shouldBe "DATA_SUBJECT_REQUEST"

            // A listagem traz o histórico; a revogação marca o registro e apaga a restrição.
            call(GET, "$path/consents", asTestUser(reader)).ok()["items"].values().map {
                it["id"].asString()
            } shouldContainExactly listOf(consentId)
            val revoked =
                call(POST, "$path/consents/$consentId/revoke", asTestUser(editor), mapOf("source" to "EMAIL")).ok()
            revoked["active"].asBoolean() shouldBe false
            revoked["revokedSource"].asString() shouldBe "EMAIL"
            revoked["revokedBy"].asString() shouldBe editor.id.toString()
            call(GET, path, asTestUser(reader)).ok()["dietaryRestrictions"].isNull shouldBe true
            column(id, "dietary_restrictions").shouldBeNull()
            // De novo: nada muda; de outro perfil ou inexistente: 404.
            call(POST, "$path/consents/$consentId/revoke", asTestUser(editor), mapOf("source" to "PHONE"))
                .ok()["revokedSource"]
                .asString() shouldBe "EMAIL"
            call(POST, "$path/consents/${UUID.randomUUID()}/revoke", asTestUser(editor), mapOf("source" to "EMAIL"))
                .problem(404, "CONSENT_NOT_FOUND")
            call(PUT, path, asTestUser(editor), dietary, ifMatch = guests.etagOf(path))
                .problem(422, "DIETARY_RESTRICTIONS_REQUIRE_CONSENT")

            // Um consentimento novo da mesma finalidade é outro registro; o revogado fica (prova).
            val again = call(POST, "$path/consents", asTestUser(editor), consent).expectStatus(201)!!
            again["id"].asString() shouldNotBe consentId
            call(GET, "$path/consents", asTestUser(reader)).ok()["items"].values().map {
                it["id"].asString()
            } shouldContainExactly listOf(again["id"].asString(), consentId)
            shouldThrow<DataAccessException> {
                jdbc.sql("DELETE FROM guests.guest_consent WHERE guest_id = :id").param("id", id).update()
            }

            // No cadastro, o consentimento vem antes dos dados: a restrição entra no mesmo pedido.
            val created =
                guests.createGuest(
                    guests.createBody(profile = dietary) +
                        ("consents" to listOf(mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "PAPER_FORM"))),
                )
            created["dietaryRestrictions"].asString() shouldBe DIETARY
            call(GET, "/api/v1/guests/${created["id"].asString()}/consents", asTestUser(reader))
                .ok()["items"][0]["grantedSource"]
                .asString() shouldBe "PAPER_FORM"
            call(POST, "/api/v1/guests", asAdmin(), guests.createBody(profile = dietary))
                .problem(422, "DIETARY_RESTRICTIONS_REQUIRE_CONSENT")

            // A trilha do consentimento: finalidade e origens, sem dado pessoal.
            val consentAudit = auditRows(listOf(UUID.fromString(consentId)), "guests.guest-consent.revoke").single()
            tree(consentAudit["after_state"])["revokedSource"].asString() shouldBe "EMAIL"
        }

    // --- Consentimentos na mesclagem e revogação sempre aceita -------------------------------------------------

    @Test
    @Suppress("LongMethod") // as três situações da mesclagem e a revogação num mesclado e num anonimizado
    fun `the merge moves consents and dietary restrictions to the survivor, and revoking is never refused`(): Unit =
        with(iam) {
            val dietaryConsent = listOf(mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "IN_PERSON"))

            // Só no absorvido: o consentimento (a mesma linha) e a restrição passam para o sobrevivente.
            val survivor = guestId(guests.createGuest(guests.createBody(documents = emptyList())))
            val merged =
                guestId(
                    guests.createGuest(
                        guests.createBody(
                            profile = guests.profileBody(extra = mapOf("dietaryRestrictions" to DIETARY)),
                            documents = emptyList(),
                        ) + ("consents" to dietaryConsent),
                    ),
                )
            val consentId = consentsOf(merged).single()["id"].asString()
            mergeInto(survivor, merged).ok()["dietaryRestrictions"].asString() shouldBe DIETARY
            consentsOf(survivor).map { it["id"].asString() to it["active"].asBoolean() } shouldBe
                listOf(consentId to true)
            // Nada fica legível nem ativo no absorvido.
            consentsOf(merged).shouldBeEmpty()
            call(GET, "/api/v1/guests/$merged", asAdmin()).ok()["dietaryRestrictions"].isNull shouldBe true
            column(merged, "dietary_restrictions").shouldBeNull()
            count(
                "SELECT count(*) FROM guests.guest_consent WHERE guest_id IN (:ids) " +
                    "AND revoked_at IS NULL AND superseded_at IS NULL",
                listOf(merged),
            ) shouldBe 0
            // A trilha registra a mudança de dono do consentimento, sem dado pessoal.
            val moved = auditRows(listOf(UUID.fromString(consentId)), "guests.guest.merge").single()
            tree(moved["before_state"])["guestId"].asString() shouldBe merged.toString()
            tree(moved["after_state"])["guestId"].asString() shouldBe survivor.toString()

            // A revogação pedida pelo id do perfil mesclado acha o consentimento no sobrevivente e apaga a dieta.
            val revoked =
                call(
                    POST,
                    "/api/v1/guests/$merged/consents/$consentId/revoke",
                    asAdmin(),
                    mapOf("source" to "EMAIL"),
                ).ok()
            revoked["active"].asBoolean() shouldBe false
            revoked["guestId"].asString() shouldBe survivor.toString()
            call(GET, "/api/v1/guests/$survivor", asAdmin()).ok()["dietaryRestrictions"].isNull shouldBe true

            // Nos dois: o sobrevivente prevalece; o do absorvido passa para ele substituído, e a dieta é a dele.
            val withBoth = { text: String ->
                guestId(
                    guests.createGuest(
                        guests.createBody(
                            profile = guests.profileBody(extra = mapOf("dietaryRestrictions" to text)),
                            documents = emptyList(),
                        ) + ("consents" to dietaryConsent),
                    ),
                )
            }
            val keeper = withBoth("Sem glúten")
            val other = withBoth("Vegano")
            val keeperConsent = consentsOf(keeper).single()["id"].asString()
            val otherConsent = consentsOf(other).single()["id"].asString()
            mergeInto(keeper, other).ok()["dietaryRestrictions"].asString() shouldBe "Sem glúten"
            val after = consentsOf(keeper).associateBy { it["id"].asString() }
            after.keys shouldBe setOf(keeperConsent, otherConsent)
            after.getValue(keeperConsent)["active"].asBoolean() shouldBe true
            after.getValue(otherConsent)["active"].asBoolean() shouldBe false
            after.getValue(otherConsent)["supersededBy"].asString() shouldBe keeperConsent
            after.getValue(otherConsent)["revokedAt"].isNull shouldBe true
            consentsOf(other).shouldBeEmpty()
            column(other, "dietary_restrictions").shouldBeNull()

            // Um consentimento que ficou num perfil mesclado (dado anterior à regra) também é revogado.
            val legacy = UuidV7.generate()
            jdbc
                .sql(
                    "INSERT INTO guests.guest_consent (id, guest_id, purpose, granted_at, granted_source) " +
                        "VALUES (:id, :guest, 'MARKETING', now(), 'EMAIL')",
                ).param("id", legacy)
                .param("guest", other)
                .update()
            call(POST, "/api/v1/guests/$other/consents/$legacy/revoke", asAdmin(), mapOf("source" to "PHONE"))
                .ok()["active"]
                .asBoolean() shouldBe false
            // Dar um consentimento num mesclado continua recusado.
            call(
                POST,
                "/api/v1/guests/$other/consents",
                asAdmin(),
                mapOf("purpose" to "MARKETING", "source" to "EMAIL"),
            ).problem(409, "GUEST_ALREADY_MERGED")

            // Num anonimizado, os consentimentos ficam (prova) e a revogação é aceita.
            val data = titular()
            call(POST, "/api/v1/guests/${data.survivor}/anonymize", asAdmin()).ok()
            val kept = consentsOf(data.survivor).single()
            call(
                POST,
                "/api/v1/guests/${data.survivor}/consents/${kept["id"].asString()}/revoke",
                asAdmin(),
                mapOf("source" to "EMAIL"),
            ).ok()["active"].asBoolean() shouldBe false
        }

    // --- Anexos --------------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o ciclo do anexo sensível: envio, vínculo, download auditado e retirada
    fun `a guest attachment is sensitive, attached through the FilesApi and downloaded only by the server URL`(): Unit =
        with(iam) {
            val clerk = createUserWith(read, attachmentsRead, attachmentsManage)
            val reader = createUserWith(read)
            val id = guestId(guests.createGuest())
            val other = guestId(guests.createGuest())
            val photo = TestImages.jpeg(PHOTO_WIDTH, PHOTO_HEIGHT)

            // Envio: só com guests.attachments.manage; o objeto fica cifrado no armazenamento.
            files
                .upload(PHOTO, asTestUser(reader), photo, "rg.jpg", "image/jpeg")
                .status shouldBe 403
            val fileId = upload(asTestUser(clerk), PHOTO, photo, "rg-maria.jpg")
            val stored = files.storedRow(fileId)
            stored["sensitive"] shouldBe true
            val key = stored["object_key"] as String
            String(files.rawObject(key).copyOfRange(0, 4), Charsets.US_ASCII) shouldBe "ZNC1"

            // Vínculo: 201, de novo 200; o files guarda o perfil como dono.
            val attachPath = "/api/v1/guests/$id/attachments"
            call(POST, attachPath, asTestUser(reader), mapOf("fileId" to fileId)).problem(403, "PERMISSION_DENIED")
            val attached = call(POST, attachPath, asTestUser(clerk), mapOf("fileId" to fileId)).expectStatus(201)!!
            attached["purpose"].asString() shouldBe PHOTO
            attached["contentType"].asString() shouldBe "image/jpeg"
            attached["attachedBy"].asString() shouldBe clerk.id.toString()
            call(POST, attachPath, asTestUser(clerk), mapOf("fileId" to fileId)).ok()["fileId"].asString() shouldBe
                fileId.toString()
            files.storedRow(fileId)["owner_entity_id"] shouldBe id.toString()
            call(GET, attachPath, asTestUser(clerk)).ok()["items"].values().map { it["fileId"].asString() } shouldBe
                listOf(fileId.toString())
            call(GET, attachPath, asTestUser(reader)).problem(403, "PERMISSION_DENIED")

            // Recusas com código do guests: outra finalidade, inexistente, já de outro perfil.
            val logo = upload(asAdmin(), "hotel.logo", TestImages.png(LOGO_SIDE, LOGO_SIDE), "logo.png")
            call(POST, attachPath, asTestUser(clerk), mapOf("fileId" to logo)).problem(422, "GUEST_ATTACHMENT_INVALID")
            call(POST, attachPath, asTestUser(clerk), mapOf("fileId" to UUID.randomUUID()))
                .problem(422, "GUEST_ATTACHMENT_INVALID")
            call(POST, "/api/v1/guests/$other/attachments", asTestUser(clerk), mapOf("fileId" to fileId))
                .problem(422, "GUEST_ATTACHMENT_INVALID")

            // Download: só com guests.attachments.read, pela URL de curta duração do próprio servidor; a emissão e
            // o download ficam na trilha, em nome de quem pediu.
            files.downloadUrl(fileId, asTestUser(reader)).status shouldBe 403
            val (url, expiresAt) = files.downloadUrlOk(fileId, asTestUser(clerk))
            url.path shouldBe "/api/v1/files/$fileId/content"
            expiresAt.isAfter(Instant.now()) shouldBe true
            expiresAt.isBefore(Instant.now().plusSeconds(MAX_URL_TTL_SECONDS + 1)) shouldBe true
            val downloaded = files.serverGet(url)
            downloaded.status shouldBe 200
            val image = TestImages.read(downloaded.contentAsByteArray)
            image.width shouldBe PHOTO_WIDTH
            val trail = files.auditOf(fileId)
            trail.filter { it.action == "files.file.issue-download-url" }.map { it.userId } shouldBe
                listOf(clerk.id.value)
            trail.filter { it.action == "files.file.download" }.map { it.userId to it.outcome } shouldBe
                listOf(clerk.id.value to "SUCCEEDED")

            // PDF numa finalidade à parte (sem reencode).
            val pdf = upload(asTestUser(clerk), PDF, FilesTestSupport.pdf("autorização"), "autorizacao.pdf")
            call(POST, attachPath, asTestUser(clerk), mapOf("fileId" to pdf)).expectStatus(201)

            // Retirar apaga o anexo (o objeto sai do armazenamento, o nome original também).
            call(POST, "$attachPath/$fileId/remove", asTestUser(clerk)).expectStatus(204)
            call(GET, attachPath, asTestUser(clerk)).ok()["items"].values().map { it["fileId"].asString() } shouldBe
                listOf(pdf.toString())
            val removed = files.storedRow(fileId)
            removed["deleted_at"].shouldNotBeNull()
            removed["original_name"].shouldBeNull()
            files.objectExists(key) shouldBe false
            files.downloadUrl(fileId, asTestUser(clerk)).status shouldBe 404
            call(POST, "$attachPath/$fileId/remove", asTestUser(clerk)).problem(404, "GUEST_ATTACHMENT_NOT_FOUND")
            files.auditOf(fileId).map { it.action } shouldContain "files.file.delete"
        }

    @Test
    fun `the guest file purposes are sensitive, reencode the photos and use guests permissions with ALL only`() {
        GuestsFilePurposes.purposes.map { it.code.value } shouldContainExactlyInAnyOrder listOf(PHOTO, MINOR, PDF)
        GuestsFilePurposes.purposes.forEach {
            withClue(it.code) {
                it.sensitive shouldBe true
                it.uploadPermission shouldBe GuestsPermissions.ATTACHMENTS_MANAGE
                it.downloadPermission shouldBe GuestsPermissions.ATTACHMENTS_READ
                (it.reencode != null) shouldBe (it.code.value != PDF)
            }
        }
        listOf(GuestsPermissions.ATTACHMENTS_READ, GuestsPermissions.ATTACHMENTS_MANAGE).forEach {
            definition(it).scopes shouldBe setOf(ALL)
        }
        definition(GuestsPermissions.DATA_EXPORT).sensitive shouldBe true
        definition(GuestsPermissions.DATA_ANONYMIZE).sensitive shouldBe true
    }

    // --- Exportação ----------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o fluxo inteiro: recusa, aprovação, pacote, trilha e as variações
    fun `the export asks for approval without the permission and returns the audited package once approved`(): Unit =
        with(iam) {
            val data = titular()
            val requester = Requester(createUserWith(read))
            val approver = approverWith(export)
            val exportPath = "/api/v1/guests/${data.survivor}/export"

            val required = call(POST, exportPath, requester.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "guests.guest.export"
            required["permission"].asString() shouldBe "guests.data.export"
            required["resourceRef"].asString() shouldBe "guests.guest:${data.survivor}"
            val approval = approve(requester, required, approver).expectStatus(201)!!
            val token = approval["approvalToken"].asString()
            val approvalId = UUID.fromString(approval["approvalId"].asString())

            val response = call(POST, exportPath, requester.auth, approvalToken = token)
            val body = response.ok()
            response.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            body["guestId"].asString() shouldBe data.survivor.toString()
            body["guestIds"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf(data.survivor.toString(), data.absorbed.toString())
            body["sections"].values().map { it["name"].asString() } shouldBe listOf("guests", "test-example.stays")
            val section = body["sections"][0]["data"]
            val profiles = section["profiles"].values().associateBy { it["id"].asString() }
            val survivor = profiles.getValue(data.survivor.toString())
            survivor["fullName"].asString() shouldBe data.name
            survivor["email"].asString() shouldBe data.email
            survivor["dietaryRestrictions"].asString() shouldBe DIETARY
            survivor["restriction"]["reason"].asString() shouldBe RESTRICTION
            // O pacote completo (LGPD art. 18): os documentos inteiros e os dados sensíveis da FNRH, só para o titular.
            survivor["documents"].values().associate { it["type"].asString() to it["number"].asString() } shouldBe
                mapOf("CPF" to data.cpf, "PASSPORT" to data.passport)
            survivor["raceColor"].asString() shouldBe "BROWN"
            survivor["disability"].asString() shouldBe "VISUAL"
            profiles.getValue(data.absorbed.toString())["email"].asString() shouldBe data.absorbedEmail
            section["consents"].values().map { it["purpose"].asString() } shouldContain "DIETARY_RESTRICTIONS"
            body["sections"][1]["data"]["guestIds"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf(data.survivor.toString(), data.absorbed.toString())
            body["attachments"].values().map { it["fileId"].asString() } shouldContainExactlyInAnyOrder
                data.files.map { it.toString() }

            // O token vale uma vez; a trilha tem o solicitante, a aprovação e o aprovador, sem os dados.
            call(POST, exportPath, requester.auth, approvalToken = token).problem(403, "APPROVAL_TOKEN_USED")
            val entries = auditByApproval("guests.guest.export", approvalId)
            entries.map { it["entity_type"] } shouldContainExactlyInAnyOrder listOf("guests.guest", "iam.approval")
            entries.forEach { it["user_id"] shouldBe requester.user.id.value }
            val exportEntry = tree(entries.single { it["entity_type"] == "guests.guest" }["after_state"])
            exportEntry["access"].asString() shouldBe "export"
            exportEntry.toString().let { audit ->
                listOf(data.name, data.email, data.cpf, data.passport, DIETARY, RESTRICTION, "BROWN", "VISUAL")
                    .forEach { audit shouldNotContain it }
            }
            tree(entries.single { it["entity_type"] == "iam.approval" }["after_state"])["approverId"]
                .asString() shouldBe approver.user.id.toString()

            // Com a permissão, sem aprovação; num perfil mesclado, 409 (o pedido é no sobrevivente).
            call(POST, exportPath, asTestUser(createUserWith(export))).ok()["guestId"].asString() shouldBe
                data.survivor.toString()
            call(POST, "/api/v1/guests/${data.absorbed}/export", asAdmin()).problem(409, "GUEST_ALREADY_MERGED")
            call(POST, "/api/v1/guests/${UUID.randomUUID()}/export", asAdmin()).problem(404, "GUEST_NOT_FOUND")
            // A resposta tem dados pessoais: a exportação não guarda a resposta pela idempotência.
            GuestPrivacyController::class.java.methods
                .single { it.name == "export" }
                .isAnnotationPresent(Idempotent::class.java) shouldBe false
        }

    // --- Anonimização --------------------------------------------------------------------------------------

    @Test
    @Suppress("LongMethod") // o fluxo inteiro e cada lugar onde um dado pessoal poderia sobrar
    fun `the anonymization erases personal data, documents, indexes and attachments and keeps the id`(): Unit =
        with(iam) {
            val data = titular()
            val requester = Requester(createUserWith(read))
            val approver = approverWith(anonymize)
            val path = "/api/v1/guests/${data.survivor}"
            val documentIds = documentIdsOf(listOf(data.survivor, data.absorbed))

            // Sem a permissão: aprovação; num mesclado: 409; com estadia ativa: 409, sem efeito.
            val required = call(POST, "$path/anonymize", requester.auth).problem(403, "APPROVAL_REQUIRED")
            required["action"].asString() shouldBe "guests.guest.anonymize"
            required["permission"].asString() shouldBe "guests.data.anonymize"
            call(POST, "/api/v1/guests/${data.absorbed}/anonymize", asAdmin()).problem(409, "GUEST_ALREADY_MERGED")
            TestActiveStays.blocked += GuestId(data.absorbed)
            call(POST, "$path/anonymize", asAdmin()).problem(409, "GUEST_HAS_ACTIVE_STAY")
            column(data.survivor, "full_name") shouldBe data.name
            TestActiveStays.blocked.clear()

            val approval = approve(requester, required, approver).expectStatus(201)!!
            val approvalId = UUID.fromString(approval["approvalId"].asString())
            val anonymized =
                call(POST, "$path/anonymize", requester.auth, approvalToken = approval["approvalToken"].asString())
                    .ok()

            // O que fica: o id, a situação, nacionalidade, país, VIP e as datas; o resto sai.
            anonymized["id"].asString() shouldBe data.survivor.toString()
            anonymized["status"].asString() shouldBe "ANONYMIZED"
            anonymized["anonymizedAt"].isNull shouldBe false
            anonymized["fullName"].asString() shouldBe "Hóspede anonimizado"
            anonymized["nationality"].asString() shouldBe "BR"
            anonymized["residenceCountry"].asString() shouldBe "BR"
            anonymized["vip"].asBoolean() shouldBe true
            listOf(
                "socialName",
                "birthDate",
                "gender",
                "email",
                "phone",
                "address",
                "notes",
                "companyId",
                "dietaryRestrictions",
                "restriction",
            ).forEach { withClue(it) { anonymized[it].isNull shouldBe true } }
            anonymized["preferences"]["floor"].isNull shouldBe true
            anonymized["documents"].values().shouldBeEmpty()
            anonymized["hasSensitiveData"].asBoolean() shouldBe false

            // No banco: nenhuma coluna dos dois perfis tem um dado pessoal; documentos e índices cegos saíram.
            val rows = rowsAsText(listOf(data.survivor, data.absorbed))
            rows.forEach { row -> data.personalValues().forEach { withClue(it) { row shouldNotContain it } } }
            jdbc
                .sql("SELECT status, anonymized_at IS NOT NULL AS anonymized FROM guests.guest WHERE id = :id")
                .param("id", data.absorbed)
                .query()
                .singleRow() shouldBe mapOf("status" to "MERGED", "anonymized" to true)
            count("SELECT count(*) FROM guests.guest_document WHERE id IN (:ids)", documentIds) shouldBe 0
            val hashes = blindIndex.candidates(IdDocuments.INDEX_PURPOSE, "CPF:BR:${data.cpf}").map { it.hash }
            count("SELECT count(*) FROM guests.guest_document WHERE number_index IN (:ids)", hashes) shouldBe 0
            guestsApi.findByDocument(DocumentType.CPF, null, data.cpf).shouldBeEmpty()
            // Os consentimentos ficam (prova).
            count("SELECT count(*) FROM guests.guest_consent WHERE guest_id IN (:ids)", listOf(data.survivor)) shouldBe
                1

            // Os anexos dos dois perfis foram apagados no files, sem o nome original e sem o objeto.
            count(
                "SELECT count(*) FROM guests.guest_attachment WHERE guest_id IN (:ids)",
                listOf(data.survivor, data.absorbed),
            ) shouldBe 0
            data.files.forEach { file ->
                val row = files.storedRow(file)
                row["deleted_at"].shouldNotBeNull()
                row["original_name"].shouldBeNull()
                files.objectExists(row["object_key"] as String) shouldBe false
            }

            // A trilha: a anonimização dos dois, com a aprovação; nenhuma entrada dos perfis tem dado pessoal.
            auditByApproval("guests.guest.anonymize", approvalId)
                .filter { it["entity_type"] == "guests.guest" }
                .map { it["entity_id"] } shouldContainExactlyInAnyOrder
                listOf(data.survivor.toString(), data.absorbed.toString())
            val trail = auditRows(listOf(data.survivor, data.absorbed) + documentIds, null)
            trail.forEach { entry ->
                val text = "${entry["before_state"]} ${entry["after_state"]}"
                data.personalValues().forEach { withClue(it) { text shouldNotContain it } }
            }

            // Depois: legível, sem dado pessoal, fora das buscas e sem nenhuma escrita.
            call(GET, "/api/v1/guests?email=${data.email}", asAdmin()).ok()["items"].values().shouldBeEmpty()
            call(PUT, path, asAdmin(), guests.profileBody(), ifMatch = "\"0\"").problem(409, "GUEST_ANONYMIZED")
            call(POST, "$path/anonymize", asAdmin()).problem(409, "GUEST_ANONYMIZED")
            call(POST, "$path/consents", asAdmin(), mapOf("purpose" to "MARKETING", "source" to "EMAIL"))
                .problem(409, "GUEST_ANONYMIZED")
            call(POST, "$path/documents", asAdmin(), guests.document("CPF", newCpf())).problem(409, "GUEST_ANONYMIZED")
            mergeInto(guestId(guests.createGuest()), data.survivor).problem(409, "GUEST_ANONYMIZED")
            guestsApi.guest(GuestId(data.survivor)).shouldNotBeNull().status shouldBe GuestStatus.ANONYMIZED
            guestsApi.restrictionAlert(GuestId(data.survivor)).shouldBeNull()
            val exported = call(POST, "$path/export", asAdmin()).ok()
            exported.toString().let { text ->
                data.personalValues().forEach { withClue(it) { text shouldNotContain it } }
            }
            exported["sections"][0]["data"]["consents"].size() shouldBe 1

            // A view: o perfil anonimizado com a UF e o município guardados.
            val view = reportRow(data.survivor)
            view["status"] shouldBe "ANONYMIZED"
            view["anonymized"] shouldBe true
            view["state"] shouldBe "SP"
            view["ibge_city_code"] shouldBe "3550308"
            view["vip"] shouldBe true
            reportRow(data.absorbed)["anonymized"] shouldBe true
        }

    // --- View de leitura -----------------------------------------------------------------------------------

    @Test
    fun `report_guests has no personal data, only SELECT for the app role, and needs nothing but the view`() {
        val id = guestId(guests.createGuest())
        val columns =
            jdbc
                .sql(
                    "SELECT column_name FROM information_schema.columns " +
                        "WHERE table_schema = 'guests' AND table_name = 'report_guests' ORDER BY ordinal_position",
                ).query(String::class.java)
                .list()
        columns shouldBe REPORT_COLUMNS
        val views =
            jdbc
                .sql(
                    "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'guests' AND c.relkind = 'v'",
                ).query(String::class.java)
                .list()
        views shouldBe listOf("report_guests")
        PRIVILEGES.filter { privilegeOn("guests.report_guests", it) } shouldBe listOf("SELECT")

        val row = reportRow(id)
        row["status"] shouldBe "ACTIVE"
        row["anonymized"] shouldBe false
        row["state"] shouldBe "SP"
        row["nationality"] shouldBe "BR"

        // Um papel que só lê a view consulta-a; as tabelas internas, não (42501).
        withViewOnlyRole { client ->
            client
                .sql("SELECT status FROM guests.report_guests WHERE guest_id = :id")
                .param("id", id)
                .query(String::class.java)
                .single() shouldBe "ACTIVE"
            val error =
                shouldThrow<DataAccessException> {
                    client.sql("SELECT count(*) FROM guests.guest").query(Long::class.java).single()
                }
            (error.mostSpecificCause as SQLException).sqlState shouldBe INSUFFICIENT_PRIVILEGE
        }
    }

    // --- Apoio ---------------------------------------------------------------------------------------------

    /** Um titular completo: o sobrevivente (com tudo) e um perfil absorvido por ele, cada um com um anexo. */
    private fun titular(): Titular =
        with(iam) {
            val cpf = newCpf()
            val name = guests.uniqueName("Titular")
            val email = guests.uniqueEmail()
            val absorbedEmail = guests.uniqueEmail()
            val passport = "PX${guests.suffix(7)}".uppercase()
            val profile =
                guests.profileBody(fullName = name, email = email, extra = mapOf("dietaryRestrictions" to DIETARY)) +
                    mapOf(
                        "vip" to true,
                        "companyId" to guests.createCompany(),
                        "socialName" to "Social ${guests.suffix()}",
                    )
            val survivor =
                guestId(
                    guests.createGuest(
                        guests.createBody(
                            profile = profile,
                            documents = listOf(guests.document("CPF", formattedCpf(cpf))),
                            sensitiveData = mapOf("raceColor" to "BROWN", "disability" to "VISUAL"),
                        ) + ("consents" to listOf(mapOf("purpose" to "DIETARY_RESTRICTIONS", "source" to "IN_PERSON"))),
                    ),
                )
            call(POST, "/api/v1/guests/$survivor/restrict", asAdmin(), mapOf("reason" to RESTRICTION)).ok()
            val absorbed =
                guestId(
                    guests.createGuest(
                        guests.createBody(
                            profile =
                                guests.profileBody(
                                    email = absorbedEmail,
                                    extra = mapOf("phone" to ABSORBED_PHONE),
                                ),
                            documents = listOf(guests.document("PASSPORT", passport, "AR")),
                        ),
                    ),
                )
            val clerk = createUserWith(attachmentsRead, attachmentsManage)
            val fileIds =
                listOf(survivor, absorbed).mapIndexed { index, guest ->
                    val content = TestImages.jpeg(PHOTO_WIDTH, PHOTO_HEIGHT + index)
                    val file = upload(asTestUser(clerk), PHOTO, content, "doc-$name.jpg")
                    call(POST, "/api/v1/guests/$guest/attachments", asTestUser(clerk), mapOf("fileId" to file))
                        .expectStatus(201)
                    file
                }
            mergeInto(survivor, absorbed).ok()
            Titular(survivor, absorbed, name, email, absorbedEmail, cpf, passport, fileIds)
        }

    private data class Titular(
        val survivor: UUID,
        val absorbed: UUID,
        val name: String,
        val email: String,
        val absorbedEmail: String,
        val cpf: String,
        val passport: String,
        val files: List<UUID>,
    ) {
        /** Os dados pessoais que nada pode guardar depois da anonimização (o endereço é o do apoio dos testes). */
        fun personalValues(): List<String> =
            listOf(
                name,
                email,
                absorbedEmail,
                cpf,
                passport,
                ABSORBED_PHONE,
                "98765-4321",
                "Rua das Acácias",
                "01407000",
                "Prefere quarto silencioso",
                RESTRICTION,
                DIETARY,
                "1990-05-20",
            )
    }

    private fun mergeInto(
        survivor: UUID,
        merged: UUID,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/guests/$survivor/merge",
            iam.asAdmin(),
            mapOf("mergedGuestId" to merged),
            ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
        )

    private fun upload(
        auth: RequestPostProcessor,
        purpose: String,
        content: ByteArray,
        name: String,
    ): UUID {
        val type =
            when {
                purpose == PDF -> "application/pdf"
                name.endsWith(".png") -> "image/png"
                else -> "image/jpeg"
            }
        val response = files.upload(purpose, auth, content, name, type)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 201 }
        return UUID.fromString(files.json(response)["id"].asString())
    }

    private fun consentsOf(guest: UUID): List<JsonNode> =
        with(iam) { call(GET, "/api/v1/guests/$guest/consents", asAdmin()).ok()["items"].values().toList() }

    private fun guestId(node: JsonNode): UUID = UUID.fromString(node["id"].asString())

    private fun column(
        id: UUID,
        column: String,
    ): String? =
        jdbc
            .sql("SELECT CAST($column AS text) FROM guests.guest WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun rowsAsText(ids: List<UUID>): List<String> =
        jdbc
            .sql("SELECT CAST(row_to_json(g) AS text) FROM guests.guest g WHERE g.id IN (:ids)")
            .param("ids", ids)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun documentIdsOf(guestIds: List<UUID>): List<UUID> =
        jdbc
            .sql("SELECT id FROM guests.guest_document WHERE guest_id IN (:ids)")
            .param("ids", guestIds)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    private fun count(
        sql: String,
        ids: List<Any>,
    ): Int =
        if (ids.isEmpty()) {
            0
        } else {
            jdbc
                .sql(sql)
                .param("ids", ids)
                .query(Int::class.java)
                .single()
        }

    private fun reportRow(id: UUID): Map<String, Any?> =
        jdbc
            .sql("SELECT * FROM guests.report_guests WHERE guest_id = :id")
            .param("id", id)
            .query()
            .singleRow()

    private fun auditRows(
        entityIds: List<UUID>,
        action: String?,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT action, entity_type, entity_id, user_id, CAST(before_state AS text) AS before_state, " +
                    "CAST(after_state AS text) AS after_state FROM audit.audit_entry " +
                    "WHERE entity_id IN (:ids) AND (CAST(:action AS text) IS NULL OR action = CAST(:action AS text)) " +
                    "ORDER BY occurred_at, id",
            ).param("ids", entityIds.map { it.toString() })
            .param("action", action)
            .query()
            .listOfRows()

    private fun auditAfter(
        entityId: UUID,
        action: String,
    ): JsonNode = tree(auditRows(listOf(entityId), action).last()["after_state"])

    private fun auditByApproval(
        action: String,
        approvalId: UUID,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT entity_type, entity_id, user_id, CAST(after_state AS text) AS after_state " +
                    "FROM audit.audit_entry WHERE action = :action AND approval_id = :id",
            ).param("action", action)
            .param("id", approvalId)
            .query()
            .listOfRows()

    private fun tree(json: Any?): JsonNode = jsonMapper.readTree(json as String)

    private fun privilegeOn(
        relation: String,
        privilege: String,
    ): Boolean =
        jdbc
            .sql("SELECT has_table_privilege(current_user, :relation, :privilege)")
            .param("relation", relation)
            .param("privilege", privilege)
            .query(Boolean::class.java)
            .single()

    /**
     * Roda [block] numa conexão do dono com `SET ROLE` para um papel novo, sem login, que só tem `USAGE` no schema e
     * `SELECT` na view (a view roda com os privilégios do dono dela). Removido no fim.
     */
    private fun withViewOnlyRole(block: (JdbcClient) -> Unit) {
        val owner = JdbcClient.create(flyway.configuration.dataSource)
        val role = "guests_probe_${UuidV7.generate().toString().takeLast(PROBE_SUFFIX).replace("-", "")}"
        owner.sql("CREATE ROLE $role NOLOGIN").update()
        try {
            owner.sql("GRANT USAGE ON SCHEMA guests TO $role").update()
            owner.sql("GRANT SELECT ON guests.report_guests TO $role").update()
            flyway.configuration.dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("SET ROLE $role") }
                block(JdbcClient.create(SingleConnectionDataSource(connection, true)))
                connection.createStatement().use { it.execute("RESET ROLE") }
            }
        } finally {
            owner.sql("DROP OWNED BY $role").update()
            owner.sql("DROP ROLE $role").update()
        }
    }

    /** O solicitante numa sessão de dispositivo fixa (o token de aprovação fica vinculado a ela). */
    private inner class Requester(
        val user: TestUser,
        val sessionId: UUID = UuidV7.generate(),
    ) {
        val auth: RequestPostProcessor get() = asUser(user.id.value, sessionId)
    }

    private data class Approver(
        val user: TestUser,
        val pin: String,
    )

    private fun approverWith(vararg grants: TestGrant): Approver {
        val user = iam.createUserWith(*grants)
        val pin = (PIN_MIN..PIN_MAX).random().toString()
        iam.setPin(user, pin)
        return Approver(user, pin)
    }

    private fun approve(
        requester: Requester,
        required: JsonNode,
        approver: Approver,
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/auth/approvals",
            requester.auth,
            mapOf(
                "action" to required["action"].asString(),
                "permission" to required["permission"].asString(),
                "resourceRef" to required["resourceRef"].asString(),
                "approverLogin" to approver.user.login,
                "approverPin" to approver.pin,
                "reason" to "Pedido do titular",
            ),
        )

    private companion object {
        const val PHOTO = "guests.id-document-photo"
        const val MINOR = "guests.minor-authorization-photo"
        const val PDF = "guests.document-pdf"
        const val DIETARY = "Sem glúten e sem lactose"
        const val RESTRICTION = "Danos ao quarto 12"
        const val ABSORBED_PHONE = "+55 21 97777-1234"
        const val PHOTO_WIDTH = 64
        const val PHOTO_HEIGHT = 48
        const val LOGO_SIDE = 32
        const val MAX_URL_TTL_SECONDS = 300L
        const val PIN_MIN = 1000
        const val PIN_MAX = 9999
        const val PROBE_SUFFIX = 12
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PRIVILEGES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
        val REPORT_COLUMNS =
            listOf(
                "guest_id",
                "status",
                "merged_into",
                "anonymized",
                "nationality",
                "residence_country",
                "state",
                "ibge_city_code",
                "vip",
                "created_at",
                "merged_at",
                "anonymized_at",
            )
    }
}
