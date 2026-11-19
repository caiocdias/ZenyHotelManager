package com.zeny.hotel.maintenance

import com.zeny.hotel.files.FilesTestConfiguration
import com.zeny.hotel.files.FilesTestSupport
import com.zeny.hotel.files.TestImages
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.iam.TestUser
import com.zeny.hotel.iam.domain.RoleId
import com.zeny.hotel.maintenance.MaintenanceTestSupport.Companion.WORK_ORDERS
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.staff.StaffApi
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Os anexos das ordens de serviço (M6 E07, Task 2, D5): as duas finalidades (foto reencodada e PDF), o vínculo pela
 * `FilesApi` (a resposta da ordem lista os vigentes, sem mudar a versão), o download pela URL assinada do `files`, as
 * recusas (`422 WORK_ORDER_ATTACHMENT_INVALID`, `409 FILE_ALREADY_ATTACHED`, ordem final), as permissões (a recepção só
 * vincula nas ordens que abriu e nunca desvincula) e o `detached_at` da retirada.
 */
@IntegrationTest
@Import(IamTestConfiguration::class, FilesTestConfiguration::class)
class WorkOrderAttachmentsTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val files: FilesTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired staffApi: StaffApi,
) {
    private val support = MaintenanceTestSupport(iam, jdbc, staffApi)

    private fun reception(): TestUser = userWithTemplate(RoleTemplate.RECEPTIONIST)

    private fun userWithTemplate(template: RoleTemplate) =
        iam.createUser().also {
            val roleId =
                jdbc
                    .sql("SELECT id FROM iam.role WHERE template_key = :key")
                    .param("key", template.name)
                    .query(UUID::class.java)
                    .single()
            iam.assignRole(it, RoleId(roleId))
        }

    private fun photo(auth: RequestPostProcessor = iam.asAdmin()): UUID =
        files.uploadOk(PHOTO, auth, TestImages.jpeg(64, 48), "defeito.jpg")

    private fun document(auth: RequestPostProcessor = iam.asAdmin()): UUID =
        files.uploadOk(DOCUMENT, auth, FilesTestSupport.pdf("orçamento"), "orcamento.pdf")

    private fun attach(
        order: UUID,
        file: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(POST, "$WORK_ORDERS/$order/attachments", auth, mapOf("fileId" to file))

    private fun detach(
        order: UUID,
        file: UUID,
        auth: RequestPostProcessor = iam.asAdmin(),
    ): MockHttpServletResponse = iam.call(POST, "$WORK_ORDERS/$order/attachments/$file/detach", auth)

    private fun attachedIds(order: JsonNode): List<String> = order["attachments"].values().map { it["id"].asString() }

    private fun isDetached(file: UUID): Boolean =
        jdbc
            .sql("SELECT detached_at, detached_by FROM maintenance.work_order_attachment WHERE file_id = :id")
            .param("id", file)
            .query { rs, _ -> rs.getObject("detached_at") != null && rs.getObject("detached_by") != null }
            .single()

    @Test
    fun `a photo with EXIF and a PDF are attached, listed on the order, and the photo downloads without EXIF`(): Unit =
        with(iam) {
            val order = support.openedId()
            val before = support.etag(order)
            val photo = photo()
            val document = document()

            val afterPhoto = attach(order, photo).ok()
            attachedIds(afterPhoto) shouldContainExactly listOf(photo.toString())
            afterPhoto["attachments"][0]["purpose"].asString() shouldBe PHOTO
            val afterDocument = attach(order, document).ok()
            attachedIds(afterDocument) shouldContainExactly listOf(photo.toString(), document.toString())
            afterDocument["attachments"][1]["purpose"].asString() shouldBe DOCUMENT

            attachedIds(support.order(order)) shouldContainExactly listOf(photo.toString(), document.toString())
            // Vincular não muda a versão da ordem.
            support.etag(order) shouldBe before
            // O anexo ficou no `files` como da ordem.
            files.storedRow(photo)["owner_entity_id"] shouldBe order.toString()

            val (url, _) = files.downloadUrlOk(photo, asAdmin())
            val download = files.httpGet(url)
            download.statusCode() shouldBe 200
            TestImages.jpegMarkers(download.body()) shouldNotContain APP1
            String(download.body(), Charsets.ISO_8859_1) shouldNotContain TestImages.METADATA_MARKER
            val (pdfUrl, _) = files.downloadUrlOk(document, asAdmin())
            files.httpGet(pdfUrl).statusCode() shouldBe 200
        }

    @Test
    fun `attaching the same file again changes nothing, and the list of other orders does not carry it`(): Unit =
        with(iam) {
            val order = support.openedId()
            val other = support.openedId()
            val photo = photo()

            attach(order, photo).ok()
            attachedIds(attach(order, photo).ok()) shouldContainExactly listOf(photo.toString())
            rowsOf(order) shouldBe 1
            attachedIds(support.order(other)).shouldBeEmpty()
            // A lista traz os anexos vigentes de cada ordem.
            val listed = support.rooms.listAll(WORK_ORDERS, asAdmin()).associateBy { it["id"].asString() }
            attachedIds(listed.getValue(order.toString())) shouldContainExactly listOf(photo.toString())
            attachedIds(listed.getValue(other.toString())).shouldBeEmpty()
        }

    @Test
    fun `a file of another purpose is 422, a file already on another order is 409, an unknown file is 400`(): Unit =
        with(iam) {
            val order = support.openedId()
            val other = support.openedId()
            val foreign = files.uploadOk("hotel.logo", asAdmin(), FilesTestSupport.png(), "logo.png")

            attach(order, foreign).problem(422, "WORK_ORDER_ATTACHMENT_INVALID")
            attachedIds(support.order(order)).shouldBeEmpty()

            val photo = photo()
            attach(other, photo).ok()
            attach(order, photo).problem(409, "FILE_ALREADY_ATTACHED")
            attachedIds(support.order(order)).shouldBeEmpty()
            attachedIds(support.order(other)) shouldContainExactly listOf(photo.toString())

            val missing = attach(order, UUID.randomUUID()).problem(400, "UNKNOWN_FILE")
            missing["errors"][0]["field"].asString() shouldBe "fileId"
            call(POST, "$WORK_ORDERS/$order/attachments", asAdmin(), mapOf("other" to "x")).status shouldBe 400
        }

    @Test
    fun `reception attaches to the order it opened and gets 403 on someone else's order and on detach`(): Unit =
        with(iam) {
            val receptionist = reception()
            val auth = asTestUser(receptionist)
            val mine = support.openedId(auth)
            val others = support.openedId()
            val photo = photo(auth)
            val second = photo(auth)

            attachedIds(attach(mine, photo, auth).ok()) shouldContainExactly listOf(photo.toString())
            attach(others, second, auth).problem(403, "PERMISSION_DENIED")
            attachedIds(support.order(others)).shouldBeEmpty()
            // Nem a desvinculação da própria ordem: só com `manage`.
            detach(mine, photo, auth).problem(403, "PERMISSION_DENIED")
            detach(others, photo, auth).problem(403, "PERMISSION_DENIED")
            attachedIds(support.order(mine)) shouldContainExactly listOf(photo.toString())
            isDetached(photo) shouldBe false
        }

    @Test
    fun `without request nor manage it is 403, even on an unknown order`(): Unit =
        with(iam) {
            val nobody = asTestUser(createUser())
            val order = support.openedId()
            val photo = photo()

            attach(order, photo, nobody).problem(403, "PERMISSION_DENIED")
            attach(UUID.randomUUID(), photo, nobody).problem(403, "PERMISSION_DENIED")
            detach(order, photo, nobody).problem(403, "PERMISSION_DENIED")
        }

    @Test
    fun `detaching with manage takes the id off the order and marks detached_at`(): Unit =
        with(iam) {
            val order = support.openedId()
            val photo = photo()
            val document = document()
            attach(order, photo).ok()
            attach(order, document).ok()
            val before = support.etag(order)

            val response = detach(order, photo).ok()
            attachedIds(response) shouldContainExactly listOf(document.toString())
            attachedIds(support.order(order)) shouldContainExactly listOf(document.toString())
            isDetached(photo) shouldBe true
            isDetached(document) shouldBe false
            support.etag(order) shouldBe before
            // O `files` devolveu o anexo a órfão.
            files.storedRow(photo)["owner_entity_id"].shouldBeNull()

            // Já desvinculado: 404; e o mesmo arquivo pode ser vinculado de novo (outra linha, vigente).
            detach(order, photo).problem(404, "WORK_ORDER_ATTACHMENT_NOT_FOUND")
            detach(order, UUID.randomUUID()).problem(404, "WORK_ORDER_ATTACHMENT_NOT_FOUND")
            attachedIds(attach(order, photo).ok()) shouldContainExactly listOf(document.toString(), photo.toString())
            rowsOf(order) shouldBe 3
        }

    @Test
    fun `a final order refuses to attach and to detach`(): Unit =
        with(iam) {
            val photo = photo()
            val kept = photo()
            val done = support.openedId()
            attach(done, kept).ok()
            support.act(done, "complete", body = mapOf<String, Any?>()).ok()
            val cancelled = support.openedId()
            support.act(cancelled, "cancel", body = mapOf("reason" to "Duplicada")).ok()

            listOf(done, cancelled).forEach { order ->
                val problem = attach(order, photo).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")
                problem["action"].asString() shouldBe "ATTACH"
            }
            detach(done, kept).problem(409, "WORK_ORDER_TRANSITION_NOT_ALLOWED")["action"].asString() shouldBe "DETACH"
            attachedIds(support.order(done)) shouldContainExactly listOf(kept.toString())
        }

    @Test
    fun `a user without read does not get the download url, and an unknown order is 404`(): Unit =
        with(iam) {
            val uploadOnly = createUserWith(TestGrant(definition(MaintenancePermissions.REQUEST), ALL))
            val auth = asTestUser(uploadOnly)
            val order = support.openedId(auth)
            val photo = photo(auth)
            attach(order, photo, auth).ok()

            files.downloadUrl(photo, auth).problem(403, "PERMISSION_DENIED")
            files.downloadUrlOk(photo, asAdmin())
            attach(UUID.randomUUID(), photo).problem(404, "WORK_ORDER_NOT_FOUND")
            detach(UUID.randomUUID(), photo).problem(404, "WORK_ORDER_NOT_FOUND")
        }

    @Test
    fun `the trail of an attachment has the ids and the purpose`(): Unit =
        with(iam) {
            val order = support.openedId()
            val photo = photo()
            attach(order, photo).ok()
            detach(order, photo).ok()

            val actions =
                jdbc
                    .sql(
                        "SELECT action FROM audit.audit_entry " +
                            "WHERE entity_type = 'maintenance.work-order-attachment' AND entity_id = :id " +
                            "ORDER BY occurred_at, id",
                    ).param("id", photo.toString())
                    .query(String::class.java)
                    .list()
            actions shouldContainExactly
                listOf("maintenance.work-order-attachment.attach", "maintenance.work-order-attachment.detach")
        }

    private fun definition(code: com.zeny.hotel.shared.PermissionCode) =
        MaintenancePermissions.permissions.single { it.code == code }

    private fun rowsOf(order: UUID): Int =
        jdbc
            .sql("SELECT count(*) FROM maintenance.work_order_attachment WHERE work_order_id = :id")
            .param("id", order)
            .query(Int::class.java)
            .single()

    private companion object {
        const val PHOTO = "maintenance.work-order-photo"
        const val DOCUMENT = "maintenance.work-order-document"
        const val APP1 = 0xE1
    }
}
