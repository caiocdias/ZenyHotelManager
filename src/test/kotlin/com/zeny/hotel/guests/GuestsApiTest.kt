package com.zeny.hotel.guests

import com.zeny.hotel.guests.GuestsTestSupport.Companion.definition
import com.zeny.hotel.guests.GuestsTestSupport.Companion.formattedCpf
import com.zeny.hotel.guests.GuestsTestSupport.Companion.newCpf
import com.zeny.hotel.guests.domain.IdDocuments
import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.iam.TestGrant
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.RoleTemplate
import com.zeny.hotel.shared.infrastructure.crypto.EncryptionKeyRing
import com.zeny.hotel.shared.infrastructure.crypto.HmacBlindIndex
import com.zeny.hotel.testsupport.IntegrationTest
import com.zeny.hotel.testsupport.TestEncryptionKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Perfis de hóspedes pela API (M3 E01, RF-HOS-01, 02 e 04, RN-19, RN-24, H8 a H10): cadastro com permissão, `If-Match`
 * e cursor; busca por nome sem acento, e-mail e documento pelo índice cego (inclusive depois de uma rotação do anel, e
 * sem decifrar os outros registros); documento mascarado e revelado só com permissão e trilha; dados sensíveis da FNRH
 * cifrados e nunca devolvidos; lista de restrição com permissão própria; trilha e log sem dado pessoal; nenhuma
 * exclusão física.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // cenários de ponta a ponta do perfil e de cada proteção
class GuestsApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val guestsApi: GuestsApi,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired @Qualifier("requestMappingHandlerMapping") private val handlerMapping: RequestMappingHandlerMapping,
) {
    private val guests = GuestsTestSupport(iam)
    private val read = TestGrant(definition(GuestsPermissions.PROFILES_READ), ALL)
    private val manage = TestGrant(definition(GuestsPermissions.PROFILES_MANAGE), ALL)
    private val reveal = TestGrant(definition(GuestsPermissions.DOCUMENTS_REVEAL), ALL)
    private val restrictions = TestGrant(definition(GuestsPermissions.RESTRICTIONS_MANAGE), ALL)

    // --- Cadastro, leitura e edição -------------------------------------------------------------------------

    @Test
    fun `a guest is created with permission, read with the ETag and edited with If-Match`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val editor = createUserWith(read, manage)
            val cpf = newCpf()
            val body = guests.createBody(documents = listOf(guests.document("CPF", formattedCpf(cpf))))

            call(POST, "/api/v1/guests", asTestUser(reader), body).problem(403, "PERMISSION_DENIED")

            val created = call(POST, "/api/v1/guests", asTestUser(editor), body)
            val createdBody = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(createdBody["id"].asString())
            created.getHeader(HttpHeaders.LOCATION) shouldBe "/api/v1/guests/$id"
            created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            createdBody["email"].asString() shouldStartWith "hospede."
            createdBody["address"]["postalCode"].asString() shouldBe "01407000"
            createdBody["preferences"]["floor"].asString() shouldBe "Alto"
            createdBody["restriction"].isNull shouldBe true
            createdBody["hasSensitiveData"].asBoolean() shouldBe false
            val document = createdBody["documents"].values().single()
            document["type"].asString() shouldBe "CPF"
            document["issuingCountry"].asString() shouldBe "BR"
            document["maskedNumber"].asString() shouldBe "***" + cpf.takeLast(4)
            created.getContentAsString(Charsets.UTF_8) shouldNotContain cpf

            // Leitura com ETag; sem permissão, 403; inexistente, 404.
            val path = "/api/v1/guests/$id"
            call(GET, path, asTestUser(reader)).getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
            call(GET, path, asTestUser(createUser())).problem(403, "PERMISSION_DENIED")
            call(GET, "/api/v1/guests/${UUID.randomUUID()}", asTestUser(reader)).problem(404, "GUEST_NOT_FOUND")

            // Edição: sem permissão 403, sem If-Match 428, versão errada 412, certa 200 com a versão nova.
            val edited =
                guests.profileBody(fullName = "Maria Edição ${guests.suffix()}", email = null, address = null) +
                    ("vip" to true)
            call(PUT, path, asTestUser(reader), edited, ifMatch = "\"0\"").problem(403, "PERMISSION_DENIED")
            call(PUT, path, asTestUser(editor), edited).problem(428, "PRECONDITION_REQUIRED")
            call(PUT, path, asTestUser(editor), edited, ifMatch = "\"7\"").problem(412, "PRECONDITION_FAILED")
            val updated = call(PUT, path, asTestUser(editor), edited, ifMatch = "\"0\"")
            updated.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
            val updatedBody = updated.ok()
            updatedBody["vip"].asBoolean() shouldBe true
            updatedBody["email"].isNull shouldBe true
            updatedBody["address"].isNull shouldBe true
            updatedBody["documents"].values().single()["maskedNumber"].asString() shouldBe "***" + cpf.takeLast(4)

            // A trilha da edição leva a lista dos campos alterados, nunca os valores (H10).
            val after = auditAfter(id, "guests.guest.update")
            after["changedFields"].values().map { it.asString() } shouldContainExactlyInAnyOrder
                listOf("fullName", "email", "address", "vip")
            after["vip"].asBoolean() shouldBe true
        }

    @Test
    fun `a foreign resident with a full address, dietary restrictions and an inactive company are refused`(): Unit =
        with(iam) {
            val foreigner = guests.profileBody(residenceCountry = "AR")
            call(POST, "/api/v1/guests", asAdmin(), guests.createBody(profile = foreigner))
                .problem(400, "ADDRESS_REQUIRES_BRAZIL_RESIDENCE")["errors"][0]["field"]
                .asString() shouldBe "address"
            // O estrangeiro informa só o país de residência.
            val onlyCountry = guests.profileBody(residenceCountry = "AR", address = null)
            guests
                .createGuest(
                    guests.createBody(
                        profile = onlyCountry,
                        documents = listOf(guests.document("PASSPORT", "AA${guests.suffix(7)}", "AR")),
                    ),
                )["residenceCountry"]
                .asString() shouldBe "AR"

            val dietary = guests.profileBody(extra = mapOf("dietaryRestrictions" to "Sem glúten"))
            call(POST, "/api/v1/guests", asAdmin(), guests.createBody(profile = dietary))
                .problem(422, "DIETARY_RESTRICTIONS_REQUIRE_CONSENT")

            val otherState = guests.address() + ("ibgeCityCode" to "3304557")
            val problem =
                call(
                    POST,
                    "/api/v1/guests",
                    asAdmin(),
                    guests.createBody(profile = guests.profileBody(address = otherState)),
                ).problem(400, "INVALID_ADDRESS")
            problem["errors"][0]["field"].asString() shouldBe "address.ibgeCityCode"
            problem.toString() shouldNotContain "3304557"

            call(
                POST,
                "/api/v1/guests",
                asAdmin(),
                guests.createBody(profile = guests.profileBody(companyId = UUID.randomUUID())),
            ).problem(400, "UNKNOWN_COMPANY")
            val company = guests.createCompany()
            call(POST, "/api/v1/companies/$company/deactivate", asAdmin()).ok()
            call(
                POST,
                "/api/v1/guests",
                asAdmin(),
                guests.createBody(profile = guests.profileBody(companyId = company)),
            ).problem(422, "COMPANY_INACTIVE")
            call(POST, "/api/v1/companies/$company/reactivate", asAdmin()).ok()
            guests
                .createGuest(guests.createBody(profile = guests.profileBody(companyId = company)))["companyId"]
                .asString() shouldBe company.toString()
        }

    @Test
    fun `an invalid CPF is refused with a stable code and the field, without echoing the number`(): Unit =
        with(iam) {
            val wrong = newCpf().let { it.dropLast(1) + ((it.last().digitToInt() + 1) % 10) }
            val problem =
                call(
                    POST,
                    "/api/v1/guests",
                    asAdmin(),
                    guests.createBody(documents = listOf(guests.document("CPF", wrong))),
                ).problem(400, "DOCUMENT_NUMBER_INVALID")
            problem["errors"][0]["field"].asString() shouldBe "documents[0].number"
            problem.toString() shouldNotContain wrong
            call(
                POST,
                "/api/v1/guests",
                asAdmin(),
                guests.createBody(documents = listOf(guests.document("CPF", formattedCpf(newCpf()), "AR"))),
            ).problem(400, "DOCUMENT_COUNTRY_INVALID")["errors"][0]["field"].asString() shouldBe
                "documents[0].issuingCountry"
            call(POST, "/api/v1/guests/search-by-document", asAdmin(), guests.document("CPF", wrong))
                .problem(400, "DOCUMENT_NUMBER_INVALID")
        }

    @Test
    fun `an invalid item inside a validated list still answers 400 VALIDATION_FAILED with its path`(): Unit =
        with(iam) {
            val problem =
                call(
                    POST,
                    "/api/v1/guests",
                    asAdmin(),
                    guests.createBody(
                        documents =
                            listOf(
                                mapOf(
                                    "type" to "CPF",
                                    "number" to " ",
                                ),
                            ),
                    ),
                ).problem(400, "VALIDATION_FAILED")
            problem["errors"].values().map { it["field"].asString() to it["code"].asString() } shouldContainExactly
                listOf("documents[0].number" to "NOT_BLANK")
        }

    // --- Busca ------------------------------------------------------------------------------------------------

    @Test
    fun `guests are found by name without accents, by e-mail and by document, page by page`(): Unit =
        with(iam) {
            val reader = createUserWith(read)
            val marker = guests.suffix(10)
            val names = listOf("Ana Conceição $marker", "Ângela Conceicão $marker", "Bruno Conceição $marker")
            val emails = names.map { guests.uniqueEmail() }
            val cpfs = names.map { newCpf() }
            val ids =
                names.indices.map { i ->
                    guests
                        .createGuest(
                            guests.createBody(
                                profile = guests.profileBody(fullName = names[i], email = emails[i]),
                                documents = listOf(guests.document("CPF", cpfs[i])),
                            ),
                        )["id"]
                        .asString()
                }

            // Nome sem acento e em outra caixa, com duas palavras; em ordem de nome normalizado.
            // O MockMvc codifica a URL: a query vai com o espaço e os acentos crus.
            val query = "CONCEICAO $marker"
            val all = call(GET, "/api/v1/guests?name=$query", asTestUser(reader)).ok()
            all["items"].values().map { it["id"].asString() } shouldContainExactly ids
            all["nextCursor"].isNull shouldBe true
            call(GET, "/api/v1/guests?name=angela $marker", asTestUser(reader))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(ids[1])

            // Páginas de 2.
            val first = call(GET, "/api/v1/guests?name=$query&limit=2", asTestUser(reader)).ok()
            first["items"].values().map { it["id"].asString() } shouldContainExactly ids.take(2)
            val cursor = first["nextCursor"].asString()
            call(GET, "/api/v1/guests?name=$query&limit=2&cursor=$cursor", asTestUser(reader))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly ids.drop(2)
            call(GET, "/api/v1/guests?name=a", asTestUser(reader)).problem(400, "VALIDATION_FAILED")
            // Curingas do LIKE no termo são texto, não curingas.
            call(GET, "/api/v1/guests?name=%_ $marker", asTestUser(reader)).ok()["items"].values().shouldBeEmpty()

            // E-mail exato, sem diferenciar maiúsculas.
            call(GET, "/api/v1/guests?email=${emails[2].uppercase()}", asTestUser(reader))
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(ids[2])

            // Documento no corpo, com ou sem pontuação.
            val found =
                call(
                    POST,
                    "/api/v1/guests/search-by-document",
                    asTestUser(reader),
                    guests.document("CPF", formattedCpf(cpfs[1])),
                ).ok()
            found["items"].values().map { it["id"].asString() } shouldContainExactly listOf(ids[1])
            found.toString() shouldNotContain cpfs[1]
            call(POST, "/api/v1/guests/search-by-document", asTestUser(reader), guests.document("CPF", newCpf()))
                .ok()["items"]
                .values()
                .shouldBeEmpty()
            call(POST, "/api/v1/guests/search-by-document", asTestUser(createUser()), guests.document("CPF", cpfs[0]))
                .problem(403, "PERMISSION_DENIED")

            // A API pública encontra pelo mesmo índice.
            guestsApi.findByDocument(DocumentType.CPF, null, cpfs[0]).map { it.id.toString() } shouldContainExactly
                listOf(ids[0])
        }

    @Test
    @Suppress("LongMethod") // o índice gravado, a rotação, o registro ilegível e a restauração
    fun `the document search uses the blind index of every key of the ring and never decrypts other records`(): Unit =
        with(iam) {
            val passport = "RT${guests.suffix(8)}".uppercase()
            val guest =
                guests.createGuest(
                    guests.createBody(documents = listOf(guests.document("PASSPORT", passport.lowercase(), "UY"))),
                )
            val guestId = UUID.fromString(guest["id"].asString())
            val row =
                jdbc
                    .sql(
                        "SELECT number, number_index_key, number_index FROM guests.guest_document WHERE guest_id = :id",
                    ).param("id", guestId)
                    .query()
                    .singleRow()
            // O número fica cifrado com a chave ativa; o índice, calculado com a chave ativa.
            (row["number"] as String) shouldStartWith "v1:${TestEncryptionKeys.ACTIVE_KEY_ID}:"
            (row["number"] as String) shouldNotContain passport
            row["number_index_key"] shouldBe TestEncryptionKeys.ACTIVE_KEY_ID
            val input = "PASSPORT:UY:$passport"
            val active =
                HmacBlindIndex(EncryptionKeyRing.parse(TestEncryptionKeys.keys, TestEncryptionKeys.ACTIVE_KEY_ID))
            row["number_index"] shouldBe active.compute(IdDocuments.INDEX_PURPOSE, input).hash

            // Rotação: o índice gravado antes dela, com a chave que era a ativa, continua encontrável.
            val previous =
                HmacBlindIndex(EncryptionKeyRing.parse(TestEncryptionKeys.keys, TestEncryptionKeys.PREVIOUS_KEY_ID))
                    .compute(IdDocuments.INDEX_PURPOSE, input)
            previous.hash shouldNotBe row["number_index"]
            jdbc
                .sql(
                    "UPDATE guests.guest_document SET number_index_key = :key, number_index = :hash " +
                        "WHERE guest_id = :id",
                ).param("key", previous.keyId)
                .param("hash", previous.hash)
                .param("id", guestId)
                .update()

            // Um outro perfil com o número ilegível: se a busca decifrasse a tabela, falharia.
            val decoy = guests.createGuest()
            val decoyId = UUID.fromString(decoy["id"].asString())
            val decoyNumber =
                jdbc
                    .sql("SELECT number FROM guests.guest_document WHERE guest_id = :id")
                    .param("id", decoyId)
                    .query(String::class.java)
                    .single()
            jdbc
                .sql("UPDATE guests.guest_document SET number = 'v1:missing:AAAA' WHERE guest_id = :id")
                .param("id", decoyId)
                .update()
            try {
                call(POST, "/api/v1/guests/search-by-document", asAdmin(), guests.document("PASSPORT", passport, "UY"))
                    .ok()["items"]
                    .values()
                    .map { it["id"].asString() } shouldContainExactly listOf(guestId.toString())
                // O mesmo número de outro país não é o mesmo documento.
                call(POST, "/api/v1/guests/search-by-document", asAdmin(), guests.document("PASSPORT", passport, "AR"))
                    .ok()["items"]
                    .values()
                    .shouldBeEmpty()
            } finally {
                jdbc
                    .sql("UPDATE guests.guest_document SET number = :number WHERE guest_id = :id")
                    .param("number", decoyNumber)
                    .param("id", decoyId)
                    .update()
            }
        }

    // --- Documentos --------------------------------------------------------------------------------------------

    @Test
    fun `documents are added, removed, shown masked and revealed only with the permission and a trail entry`(): Unit =
        with(iam) {
            val editor = createUserWith(read, manage)
            val revealer = createUserWith(read, reveal)
            val guest = guests.createGuest(guests.createBody(documents = emptyList()))
            val id = guest["id"].asString()
            val passport = "PX${guests.suffix(7)}".uppercase()

            call(
                POST,
                "/api/v1/guests/$id/documents",
                asTestUser(revealer),
                guests.document("PASSPORT", passport, "PT"),
            ).problem(403, "PERMISSION_DENIED")
            val added =
                call(
                    POST,
                    "/api/v1/guests/$id/documents",
                    asTestUser(editor),
                    guests.document("PASSPORT", passport, "PT"),
                )
            added.getHeader(HttpHeaders.ETAG) shouldNotBe "\"0\""
            val document = added.ok()["documents"].values().single()
            document["maskedNumber"].asString() shouldBe "***" + passport.takeLast(4)
            val documentId = document["id"].asString()
            call(POST, "/api/v1/guests/$id/documents", asTestUser(editor), guests.document("PASSPORT", passport, "PT"))
                .problem(409, "DOCUMENT_ALREADY_RECORDED")

            // Revelar: sem a permissão 403 (nem quem edita tem); com ela, 200, sem cache e com a entrada na trilha.
            val revealPath = "/api/v1/guests/$id/documents/$documentId/reveal"
            call(POST, revealPath, asTestUser(editor)).problem(403, "PERMISSION_DENIED")
            auditEntries(UUID.fromString(documentId), "guests.guest-document.reveal").shouldBeEmpty()
            val revealed = call(POST, revealPath, asTestUser(revealer))
            revealed.getHeader(HttpHeaders.CACHE_CONTROL) shouldBe "no-store"
            val revealedBody = revealed.ok()
            revealedBody["number"].asString() shouldBe passport
            revealedBody["issuingCountry"].asString() shouldBe "PT"
            val entry = auditEntries(UUID.fromString(documentId), "guests.guest-document.reveal").single()
            entry["user_id"] shouldBe revealer.id.value
            (entry["after_state"] as Any).toString() shouldNotContain passport
            (entry["after_state"] as Any).toString().contains("reveal") shouldBe true
            call(POST, "/api/v1/guests/$id/documents/${UUID.randomUUID()}/reveal", asTestUser(revealer))
                .problem(404, "GUEST_DOCUMENT_NOT_FOUND")

            // Retirar: o documento sai; de outro perfil, 404.
            val other = guests.createGuest()["id"].asString()
            call(POST, "/api/v1/guests/$other/documents/$documentId/remove", asTestUser(editor))
                .problem(404, "GUEST_DOCUMENT_NOT_FOUND")
            call(POST, "/api/v1/guests/$id/documents/$documentId/remove", asTestUser(editor))
                .ok()["documents"]
                .values()
                .shouldBeEmpty()
            auditEntries(UUID.fromString(documentId), "guests.guest-document.remove").single()
        }

    // --- Dados sensíveis da FNRH --------------------------------------------------------------------------------

    @Test
    fun `sensitive FNRH data is stored encrypted, never returned, and read only through the FNRH method`(): Unit =
        with(iam) {
            val editor = createUserWith(read, manage)
            val created =
                call(
                    POST,
                    "/api/v1/guests",
                    asTestUser(editor),
                    guests.createBody(sensitiveData = mapOf("raceColor" to "INDIGENOUS", "disability" to "VISUAL_LOW")),
                )
            val body = created.expectStatus(201).shouldNotBeNull()
            val id = UUID.fromString(body["id"].asString())
            body["hasSensitiveData"].asBoolean() shouldBe true
            listOf(
                created.getContentAsString(Charsets.UTF_8),
                call(GET, "/api/v1/guests/$id", asTestUser(editor)).ok().toString(),
            ).forEach { json ->
                json shouldNotContain "raceColor"
                json shouldNotContain "disability"
                json shouldNotContain "INDIGENOUS"
                json shouldNotContain "VISUAL_LOW"
            }

            // No banco: um único valor cifrado, de tamanho fixo (o tamanho não revela a resposta).
            val stored = storedSensitiveData(id)
            stored shouldStartWith "v1:${TestEncryptionKeys.ACTIVE_KEY_ID}:"
            stored shouldNotContain "INDIGENOUS"
            val updated =
                call(PUT, "/api/v1/guests/$id/sensitive-data", asTestUser(editor), mapOf("raceColor" to "WHITE"))
            updated.ok()["hasSensitiveData"].asBoolean() shouldBe true
            updated.getContentAsString(Charsets.UTF_8) shouldNotContain "WHITE"
            storedSensitiveData(id).length shouldBe stored.length

            // Só a API da FNRH os decifra.
            val fnrh = guestsApi.fnrhData(GuestId(id)).shouldNotBeNull()
            fnrh.raceColor shouldBe RaceColor.WHITE
            fnrh.disability.shouldBeNull()
            fnrh.documents.single().type shouldBe DocumentType.CPF
            fnrh.toString() shouldNotContain "WHITE"

            // Sem permissão de edição: 403; código fora do formato: 400; os dois nulos apagam.
            call(
                PUT,
                "/api/v1/guests/$id/sensitive-data",
                asTestUser(createUserWith(read)),
                mapOf("raceColor" to "BLACK"),
            ).problem(403, "PERMISSION_DENIED")
            call(PUT, "/api/v1/guests/$id/sensitive-data", asTestUser(editor), mapOf("disability" to "cego"))
                .problem(400, "SENSITIVE_DATA_INVALID")
            call(PUT, "/api/v1/guests/$id/sensitive-data", asTestUser(editor), emptyMap<String, Any>())
                .ok()["hasSensitiveData"]
                .asBoolean() shouldBe false
            storedSensitiveDataOrNull(id).shouldBeNull()
            auditEntries(id, "guests.guest.record-sensitive-data").size shouldBe 2
        }

    // --- Lista de restrição -------------------------------------------------------------------------------------

    @Test
    fun `the restriction list needs its own permission, is audited and raises the alert of the public API`(): Unit =
        with(iam) {
            val editor = createUserWith(read, manage)
            val manager = createUserWith(read, restrictions)
            val id = guests.createGuest()["id"].asString()
            val guestId = GuestId(UUID.fromString(id))
            val reason = "Danos ao quarto em ${guests.suffix(6)}"

            call(POST, "/api/v1/guests/$id/restrict", asTestUser(editor), mapOf("reason" to reason))
                .problem(403, "PERMISSION_DENIED")
            guestsApi.restrictionAlert(guestId).shouldBeNull()

            val restricted =
                call(
                    POST,
                    "/api/v1/guests/$id/restrict",
                    asTestUser(manager),
                    mapOf("reason" to reason),
                ).ok()
            restricted["restriction"]["reason"].asString() shouldBe reason
            restricted["restriction"]["restrictedBy"].asString() shouldBe manager.id.value.toString()
            val alert = guestsApi.restrictionAlert(guestId).shouldNotBeNull()
            alert.reason shouldBe reason
            call(GET, "/api/v1/guests?restricted=true&name=${restricted["fullName"].asString()}", asAdmin())
                .ok()["items"]
                .values()
                .map { it["id"].asString() } shouldContainExactly listOf(id)

            call(POST, "/api/v1/guests/$id/lift-restriction", asTestUser(editor)).problem(403, "PERMISSION_DENIED")
            call(POST, "/api/v1/guests/$id/lift-restriction", asTestUser(manager)).ok()["restriction"].isNull shouldBe
                true
            guestsApi.restrictionAlert(guestId).shouldBeNull()
            auditEntries(UUID.fromString(id), "guests.guest.restrict").single()["user_id"] shouldBe manager.id.value
            auditEntries(UUID.fromString(id), "guests.guest.lift-restriction").single()
        }

    // --- Trilha, log, API pública e exclusão --------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    @Suppress("LongMethod") // todas as escritas do perfil e a lista do que não pode aparecer
    fun `every write is audited and neither the trail nor the log has personal data in clear`(
        output: CapturedOutput,
    ): Unit =
        with(iam) {
            val cpf = newCpf()
            val name = guests.uniqueName("Teodora")
            val email = guests.uniqueEmail()
            val reason = "Motivo reservado ${guests.suffix(6)}"
            val created =
                guests.createGuest(
                    guests.createBody(
                        profile = guests.profileBody(fullName = name, email = email),
                        documents = listOf(guests.document("CPF", formattedCpf(cpf))),
                        sensitiveData = mapOf("raceColor" to "BROWN", "disability" to "HEARING_FULL"),
                    ),
                )
            val id = UUID.fromString(created["id"].asString())
            val documentId = created["documents"][0]["id"].asString()
            val path = "/api/v1/guests/$id"
            call(
                PUT,
                path,
                asAdmin(),
                guests.profileBody(fullName = "$name Filho", email = email),
                ifMatch = "\"0\"",
            ).ok()
            call(POST, "$path/documents", asAdmin(), guests.document("RG", "12.345.678-9")).ok()
            call(POST, "$path/documents/$documentId/reveal", asAdmin()).ok()
            call(PUT, "$path/sensitive-data", asAdmin(), mapOf("raceColor" to "YELLOW")).ok()
            call(POST, "$path/restrict", asAdmin(), mapOf("reason" to reason)).ok()
            call(POST, "$path/lift-restriction", asAdmin()).ok()

            val documentIds =
                jdbc
                    .sql("SELECT id FROM guests.guest_document WHERE guest_id = :id")
                    .param("id", id)
                    .query(UUID::class.java)
                    .list()
            val entries =
                jdbc
                    .sql(
                        "SELECT action, CAST(before_state AS text) AS before_state, " +
                            "CAST(after_state AS text) AS after_state " +
                            "FROM audit.audit_entry WHERE entity_id IN (:ids) ORDER BY occurred_at",
                    ).param("ids", (documentIds + id).map { it.toString() })
                    .query()
                    .listOfRows()
            entries.map { it["action"] } shouldContainExactlyInAnyOrder
                listOf(
                    "guests.guest.create",
                    "guests.guest.update",
                    "guests.guest-document.add",
                    "guests.guest-document.reveal",
                    "guests.guest.record-sensitive-data",
                    "guests.guest.restrict",
                    "guests.guest.lift-restriction",
                )
            val trail = entries.joinToString("\n") { "${it["before_state"]} ${it["after_state"]}" }
            val secrets =
                listOf(
                    name,
                    "Teodora",
                    email,
                    cpf,
                    formattedCpf(cpf),
                    "123456789",
                    "12.345.678-9",
                    "98765-4321",
                    "Acácias",
                    "01407000",
                    "Prefere quarto",
                    reason,
                    "BROWN",
                    "YELLOW",
                    "HEARING_FULL",
                    "1990-05-20",
                )
            secrets.forEach { trail shouldNotContain it }
            trail.contains("changedFields") shouldBe true
            trail.contains("\"type\": \"CPF\"") shouldBe true

            // Nem o log: nome, contatos, CPF e RG (normalizados e formatados), endereço, motivo e dados sensíveis.
            secrets.forEach { output.all shouldNotContain it }
        }

    @Test
    fun `the public API reads profiles, minors and companies, and nothing is deleted`(): Unit =
        with(iam) {
            val company = guests.createCompany()
            val profile =
                guests.profileBody(companyId = company) + ("birthDate" to LocalDate.now().minusYears(17).toString())
            val id = GuestId(UUID.fromString(guests.createGuest(guests.createBody(profile = profile))["id"].asString()))

            val summary = guestsApi.guest(id).shouldNotBeNull()
            summary.companyId shouldBe CompanyId(company)
            summary.toString() shouldNotContain summary.fullName
            guestsApi.isMinorOn(id, LocalDate.now()) shouldBe true
            guestsApi.isMinorOn(id, LocalDate.now().plusYears(2)) shouldBe false
            guestsApi.guest(GuestId(UUID.randomUUID())).shouldBeNull()
            guestsApi.company(CompanyId(company)).shouldNotBeNull().active shouldBe true

            shouldThrow<DataAccessException> {
                jdbc.sql("DELETE FROM guests.guest WHERE id = :id").param("id", id.value).update()
            }
            shouldThrow<DataAccessException> {
                jdbc.sql("DELETE FROM guests.company WHERE id = :id").param("id", company).update()
            }
            handlerMapping.handlerMethods
                .filter { (_, method) -> method.beanType.packageName.startsWith("com.zeny.hotel.guests") }
                .keys
                .flatMap { it.methodsCondition.methods }
                .map { it.name }
                .toSet() shouldBe setOf("GET", "POST", "PUT")
        }

    @Test
    fun `the suggested templates give the receptionist, the manager and finance their permissions`() {
        GuestsPermissions.permissions
            .filter { RoleTemplate.RECEPTIONIST in it.templates }
            .map { it.code } shouldContainExactlyInAnyOrder
            listOf(
                GuestsPermissions.PROFILES_READ,
                GuestsPermissions.PROFILES_MANAGE,
                GuestsPermissions.DOCUMENTS_REVEAL,
                GuestsPermissions.COMPANIES_READ,
                GuestsPermissions.ATTACHMENTS_READ,
                GuestsPermissions.ATTACHMENTS_MANAGE,
            )
        GuestsPermissions.permissions.count { RoleTemplate.MANAGER in it.templates } shouldBe
            GuestsPermissions.permissions.size
        GuestsPermissions.permissions.filter { RoleTemplate.FINANCE in it.templates }.map { it.code } shouldContain
            GuestsPermissions.COMPANIES_MANAGE
        // Só a exportação e a anonimização dos dados do titular são sensíveis (RF-ACS-08; decisão do usuário em
        // 2026-10-17 para a anonimização).
        GuestsPermissions.permissions.filter { it.sensitive }.map { it.code } shouldContainExactlyInAnyOrder
            listOf(GuestsPermissions.DATA_EXPORT, GuestsPermissions.DATA_ANONYMIZE)
    }

    private fun storedSensitiveData(id: UUID): String = storedSensitiveDataOrNull(id).shouldNotBeNull()

    private fun storedSensitiveDataOrNull(id: UUID): String? =
        jdbc
            .sql("SELECT sensitive_data FROM guests.guest WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun auditEntries(
        entityId: UUID,
        action: String,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT user_id, CAST(after_state AS text) AS after_state FROM audit.audit_entry " +
                    "WHERE entity_id = :id AND action = :action ORDER BY occurred_at",
            ).param("id", entityId.toString())
            .param("action", action)
            .query()
            .listOfRows()

    private fun auditAfter(
        entityId: UUID,
        action: String,
    ): JsonNode = jsonMapper.readTree(auditEntries(entityId, action).single()["after_state"] as String)
}
