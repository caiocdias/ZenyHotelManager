package com.zeny.hotel.guests

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.FieldCipher
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Ataque à escrita da descrição do gênero pela API (M5 E15, Task 2): entrada externa (nula, vazia, só espaços,
 * controle, 100 e 101 caracteres com acento e emoji), corpo de erro e log sem o valor (inclusive `@Size` e JSON
 * malformado), todos os outros gêneros, `?dataSubjectRequest=true`, perfil mesclado e anonimizado, trilha sem o valor,
 * regra do gênero na mesclagem e o contrato gerado.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LargeClass") // um cenário por ataque
class GuestGenderDescriptionApiTest(
    @Autowired private val iam: IamTestSupport,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val jsonMapper: JsonMapper,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val cipher: FieldCipher,
) {
    private val guests = GuestsTestSupport(iam)

    // --- Entrada externa ------------------------------------------------------------------------------------

    @Test
    fun `a blank description is stored as null, with OTHER or with another gender`(): Unit =
        with(iam) {
            listOf("", "   ", "\t \n").forEach { blank ->
                withClue("OTHER com [${blank.replace("\n", "\\n")}]") {
                    val id = create("OTHER", blank)
                    stored(id).shouldBeNull()
                }
                withClue("MALE com [${blank.replace("\n", "\\n")}]") {
                    val id = create("MALE", blank)
                    stored(id).shouldBeNull()
                }
            }
        }

    @Test
    fun `an explicit null or an omitted description is stored as null`(): Unit =
        with(iam) {
            stored(create("OTHER", null)).shouldBeNull()
            val omitted = guests.profileBody(extra = mapOf("gender" to "OTHER")) - "genderDescription"
            val id = UUID.fromString(guests.createGuest(guests.createBody(profile = omitted))["id"].asString())
            stored(id).shouldBeNull()
        }

    @Test
    fun `a description is trimmed before it is stored`(): Unit =
        with(iam) {
            val id = create("OTHER", "   Pessoa agênero  \n")
            cipher.decrypt(stored(id).shouldNotBeNull()) shouldBe "Pessoa agênero"
        }

    @Test
    fun `100 accented characters are accepted and 101 are refused on create and update`(): Unit =
        with(iam) {
            val ok100 = "é".repeat(100)
            val id = create("OTHER", ok100)
            cipher.decrypt(stored(id).shouldNotBeNull()) shouldBe ok100

            val path = "/api/v1/guests/$id"
            val bad101 = "ç".repeat(101)
            val before = stored(id)
            val onUpdate =
                call(PUT, path, otherProfile(bad101), ifMatch = "\"0\"").problem(400, "VALIDATION_FAILED")
            onUpdate["errors"][0]["field"].asString() shouldBe "genderDescription"
            onUpdate.toString() shouldNotContain bad101
            stored(id) shouldBe before

            val onCreate = call(POST, "/api/v1/guests", guests.createBody(profile = otherProfile(bad101)))
            onCreate.problem(400, "VALIDATION_FAILED")["errors"][0]["field"].asString() shouldBe
                "profile.genderDescription"
        }

    @Test
    fun `the limit of 100 counts the characters Java counts, so an emoji takes two`(): Unit =
        with(iam) {
            // 50 emojis = 100 unidades UTF-16 (50 pontos de código): aceitos.
            val fifty = "😀".repeat(50)
            cipher.decrypt(stored(create("OTHER", fifty)).shouldNotBeNull()) shouldBe fifty

            // 51 emojis = 102 unidades: recusados no @Size, sem eco do valor.
            val fiftyOne = "😀".repeat(51)
            val problem = call(POST, "/api/v1/guests", guests.createBody(profile = otherProfile(fiftyOne)))
            problem.problem(400, "VALIDATION_FAILED").toString() shouldNotContain fiftyOne
        }

    @Test
    fun `the size check looks at the raw text, so 100 characters plus a space are refused`(): Unit =
        with(iam) {
            // O @Size olha o texto cru: 100 caracteres mais espaços passam a 101 e são recusados, mesmo que o
            // domínio apare para 100. Documenta o comportamento (400 VALIDATION_FAILED), sem eco.
            val padded = "a".repeat(100) + " "
            val response = call(POST, "/api/v1/guests", guests.createBody(profile = otherProfile(padded)))
            response.problem(400, "VALIDATION_FAILED")["errors"][0]["field"].asString() shouldBe
                "profile.genderDescription"
        }

    @Test
    fun `control characters never cause a server error or an echo of the text`(): Unit =
        with(iam) {
            listOf("a\u0000b-SEGREDO1", "linha1\nlinha2-SEGREDO2", "tab\there-SEGREDO3", "esc\u001b[31m-SEGREDO4")
                .forEach { text ->
                    val response = call(POST, "/api/v1/guests", guests.createBody(profile = otherProfile(text)))
                    withClue("controle: ${response.getContentAsString(Charsets.UTF_8)}") {
                        (response.status in listOf(201, 400)) shouldBe true
                    }
                    response.getContentAsString(Charsets.UTF_8) shouldNotContain "SEGREDO"
                }
        }

    @Test
    fun `a non OTHER gender refuses a description on create and update, for every gender`(): Unit =
        with(iam) {
            val others = Gender.entries.filter { it != Gender.OTHER }
            others.forEach { gender ->
                withClue("POST $gender") {
                    val text = "Texto reservado POST ${guests.suffix(6)}"
                    val body = guests.createBody(profile = profileWith(gender.name, text))
                    val problem = call(POST, "/api/v1/guests", body)
                    problem
                        .problem(400, "GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER")["errors"][0]["field"]
                        .asString() shouldBe "genderDescription"
                    problem.toString() shouldNotContain text
                }
            }
            val id = create("OTHER", "Inicial ${guests.suffix(6)}")
            val before = stored(id)
            others.forEach { gender ->
                withClue("PUT $gender") {
                    val text = "Texto reservado PUT ${guests.suffix(6)}"
                    val problem =
                        call(
                            PUT,
                            "/api/v1/guests/$id",
                            profileWith(gender.name, text),
                            ifMatch = "\"0\"",
                        )
                    problem.problem(400, "GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER")
                    problem.getContentAsString(Charsets.UTF_8) shouldNotContain text
                }
            }
            stored(id) shouldBe before
        }

    @Test
    fun `a gender omitted with a description is refused like another gender`(): Unit =
        with(iam) {
            val text = "Sem gênero ${guests.suffix(6)}"
            val body = guests.profileBody(extra = mapOf("gender" to null, "genderDescription" to text))
            call(POST, "/api/v1/guests", guests.createBody(profile = body))
                .problem(400, "GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER")
                .toString() shouldNotContain text
        }

    @Test
    fun `resending OTHER without the description clears it, as a PUT replaces the profile`(): Unit =
        with(iam) {
            val id = create("OTHER", "Algo ${guests.suffix(6)}")
            stored(id).shouldNotBeNull()
            call(
                PUT,
                "/api/v1/guests/$id",
                guests.profileBody(extra = mapOf("gender" to "OTHER")),
                ifMatch = "\"0\"",
            ).ok()
            stored(id).shouldBeNull()
        }

    @Test
    fun `resending the same description changes nothing, keeps the ciphertext and writes no audit entry`(): Unit =
        with(iam) {
            val text = "Igual ${guests.suffix(6)}"
            val profile = otherProfile(text)
            val id =
                UUID.fromString(
                    guests.createGuest(guests.createBody(profile = profile, documents = emptyList()))["id"].asString(),
                )
            val before = stored(id)
            call(PUT, "/api/v1/guests/$id", profile, ifMatch = "\"0\"").ok()
            stored(id) shouldBe before
            auditRows(id, "guests.guest.update").shouldBeEmpty()
        }

    // --- Corpo de erro e log sem o valor --------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `neither the error body nor the log has the text, in validation, rule and malformed JSON errors`(
        output: CapturedOutput,
    ): Unit =
        with(iam) {
            val tag = "SEGREDO${guests.suffix(8)}"
            val id = create("OTHER", "Inicial")

            // @Size
            val tooLong = tag + "x".repeat(101)
            val size = call(PUT, "/api/v1/guests/$id", otherProfile(tooLong), ifMatch = "\"0\"")
            size.problem(400, "VALIDATION_FAILED")
            size.getContentAsString(Charsets.UTF_8) shouldNotContain tag

            // Regra do gênero.
            val rule =
                call(PUT, "/api/v1/guests/$id", profileWith("MALE", tag), ifMatch = "\"0\"")
            rule.problem(400, "GENDER_DESCRIPTION_REQUIRES_OTHER_GENDER")
            rule.getContentAsString(Charsets.UTF_8) shouldNotContain tag

            // JSON truncado, com o valor dentro.
            val truncated = """{"fullName":"X","gender":"OTHER","genderDescription":"$tag"""
            val malformed = raw(PUT, "/api/v1/guests/$id", truncated, ifMatch = "\"0\"")
            withClue(malformed.getContentAsString(Charsets.UTF_8)) { malformed.status shouldBe 400 }
            malformed.getContentAsString(Charsets.UTF_8) shouldNotContain tag

            // Tipo errado (objeto no lugar do texto) e gênero inválido, com o valor no corpo.
            val wrongType = """{"fullName":"X","gender":"OTHER","genderDescription":{"a":"$tag"}}"""
            val typed = raw(PUT, "/api/v1/guests/$id", wrongType, ifMatch = "\"0\"")
            withClue(typed.getContentAsString(Charsets.UTF_8)) { typed.status shouldBe 400 }
            typed.getContentAsString(Charsets.UTF_8) shouldNotContain tag
            val badGender = """{"fullName":"X","gender":"$tag","genderDescription":"$tag"}"""
            val enum = raw(PUT, "/api/v1/guests/$id", badGender, ifMatch = "\"0\"")
            withClue(enum.getContentAsString(Charsets.UTF_8)) { enum.status shouldBe 400 }
            enum.getContentAsString(Charsets.UTF_8) shouldNotContain tag

            // No POST, o mesmo JSON truncado.
            val createTruncated =
                raw(POST, "/api/v1/guests", """{"profile":{"gender":"OTHER","genderDescription":"$tag""")
            createTruncated.status shouldBe 400
            createTruncated.getContentAsString(Charsets.UTF_8) shouldNotContain tag

            output.all shouldNotContain tag
        }

    // --- ?dataSubjectRequest=true e trilha -------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `a data subject request update records the origin and the field name, never the text`(
        output: CapturedOutput,
    ): Unit =
        with(iam) {
            val first = "Primeira ${guests.suffix(8)}-REINO"
            val second = "Segunda ${guests.suffix(8)}-REINO"
            val id = create("OTHER", first)
            call(PUT, "/api/v1/guests/$id?dataSubjectRequest=true", otherProfile(second), ifMatch = "\"0\"").ok()

            cipher.decrypt(stored(id).shouldNotBeNull()) shouldBe second
            val row = auditRows(id, "guests.guest.update").single()
            val after = jsonMapper.readTree(row["after_state"] as String)
            after["origin"].asString() shouldBe "DATA_SUBJECT_REQUEST"
            after["changedFields"].values().map { it.asString() } shouldContain "genderDescription"
            auditText(id) shouldNotContain "REINO"
            auditText(id) shouldNotContain first
            auditText(id) shouldNotContain second
            output.all shouldNotContain "REINO"
        }

    @Test
    fun `the audit rows of create and of a change to another gender carry no text and no field value`(): Unit =
        with(iam) {
            val text = "Reservada ${guests.suffix(8)}-CRIA"
            val id = create("OTHER", text)
            // OTHER -> MALE sem descrição: o campo muda (apagado) e não aparece valor nenhum.
            call(PUT, "/api/v1/guests/$id", profileWith("MALE", null), ifMatch = "\"0\"").ok()
            val changed =
                jsonMapper
                    .readTree(auditRows(id, "guests.guest.update").single()["after_state"] as String)["changedFields"]
                    .values()
                    .map { it.asString() }
            changed shouldContain "genderDescription"
            changed shouldContain "gender"
            auditText(id) shouldNotContain "CRIA"
            auditText(id) shouldNotContain "v1:"
        }

    // --- Perfil mesclado e anonimizado ----------------------------------------------------------------------

    @Test
    fun `a merged profile refuses a write and keeps its stored description`(): Unit =
        with(iam) {
            val survivor = create("MALE", null)
            val absorbed = create("OTHER", "Absorvida ${guests.suffix(6)}")
            merge(survivor, absorbed).ok()
            val before = stored(absorbed)

            val response =
                call(
                    PUT,
                    "/api/v1/guests/$absorbed",
                    otherProfile("Nova ${guests.suffix(6)}"),
                    ifMatch = guests.etagOf("/api/v1/guests/$absorbed"),
                )
            response.problem(409, "GUEST_ALREADY_MERGED")
            stored(absorbed) shouldBe before
        }

    @Test
    fun `anonymizing the survivor erases the description of the survivor and of the absorbed profile`(): Unit =
        with(iam) {
            val survivor = create("OTHER", "Sobrevivente ${guests.suffix(6)}")
            val absorbed = create("OTHER", "Absorvido ${guests.suffix(6)}")
            merge(survivor, absorbed).ok()
            stored(survivor).shouldNotBeNull()

            call(POST, "/api/v1/guests/$survivor/anonymize", asAdmin()).ok()
            stored(survivor).shouldBeNull()
            stored(absorbed).shouldBeNull()
        }

    @Test
    fun `an anonymized profile refuses a new description and stays without one`(): Unit =
        with(iam) {
            val id = create("OTHER", "Antes ${guests.suffix(6)}")
            call(POST, "/api/v1/guests/$id/anonymize", asAdmin()).ok()
            val response =
                call(
                    PUT,
                    "/api/v1/guests/$id",
                    otherProfile("Depois ${guests.suffix(6)}"),
                    ifMatch = guests.etagOf("/api/v1/guests/$id"),
                )
            withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 409 }
            stored(id).shouldBeNull()
        }

    // --- Mesclagem ------------------------------------------------------------------------------------------

    @Test
    fun `the merge keeps a description with the gender it belongs to`(): Unit =
        with(iam) {
            val ownA = "Dona A ${guests.suffix(6)}"
            val ownB = "Dona B ${guests.suffix(6)}"

            // OTHER (A) absorve OTHER (B) sem GENDER: fica A.
            val keepA = create("OTHER", ownA)
            merge(keepA, create("OTHER", ownB)).ok()
            cipher.decrypt(stored(keepA).shouldNotBeNull()) shouldBe ownA

            // OTHER (A) absorve OTHER (B) com GENDER: fica B.
            val takeB = create("OTHER", ownA)
            merge(takeB, create("OTHER", ownB), listOf("GENDER")).ok()
            cipher.decrypt(stored(takeB).shouldNotBeNull()) shouldBe ownB

            // OTHER (A) absorve MALE com GENDER: vira MALE e a descrição some.
            val becomesMale = create("OTHER", ownA)
            merge(becomesMale, create("MALE", null), listOf("GENDER")).ok()
            stored(becomesMale).shouldBeNull()

            // MALE absorve OTHER com GENDER: vira OTHER com a descrição do absorvido.
            val becomesOther = create("MALE", null)
            merge(becomesOther, create("OTHER", ownB), listOf("GENDER")).ok()
            cipher.decrypt(stored(becomesOther).shouldNotBeNull()) shouldBe ownB

            // OTHER com descrição absorve OTHER sem descrição, com GENDER: a descrição do sobrevivente fica.
            val keepsOwn = create("OTHER", ownA)
            merge(keepsOwn, create("OTHER", null), listOf("GENDER")).ok()
            cipher.decrypt(stored(keepsOwn).shouldNotBeNull()) shouldBe ownA
        }

    @Test
    fun `the merge audit trail has no description text`(): Unit =
        with(iam) {
            val survivor = create("MALE", null)
            val absorbed = create("OTHER", "Mescla ${guests.suffix(8)}-MESCLA")
            merge(survivor, absorbed, listOf("GENDER")).ok()
            auditText(survivor) shouldNotContain "MESCLA"
            auditText(absorbed) shouldNotContain "MESCLA"
        }

    // --- Relatórios e contrato ------------------------------------------------------------------------------

    @Test
    fun `no report view exposes a gender column`() {
        jdbc
            .sql(
                "SELECT table_name || '.' || column_name FROM information_schema.columns " +
                    "WHERE table_name LIKE 'report\\_%' AND column_name ILIKE '%gender%'",
            ).query(String::class.java)
            .list()
            .shouldBeEmpty()
    }

    @Test
    fun `the contract adds the field only to the profile request, with the limit and without a pattern`() {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val block = schemaBlock(lines, "GuestProfileRequest")
        val field = propertyBlock(block, "genderDescription")
        field shouldContain "maxLength: 100"
        field shouldContain "type: string"
        field.contains("pattern:") shouldBe false
        field.contains("example") shouldBe false
        // Nenhum outro esquema de requisição ganhou o campo.
        val requests = lines.indices.filter { lines[it].matches(Regex("^    \\w+Request:$")) }
        requests
            .map { lines[it].trim().removeSuffix(":") }
            .filter { it != "GuestProfileRequest" }
            .forEach { name ->
                withClue(name) {
                    schemaBlock(lines, name).none { it.trim() == "genderDescription:" } shouldBe true
                }
            }
    }

    @Test
    fun `the contract does not expose the description in any response schema`() {
        val lines = Files.readAllLines(Path.of("api/openapi.yaml"))
        val names =
            lines
                .filter { it.matches(Regex("^    Guest\\w*(Response|View|Summary|Item):$")) }
                .map { it.trim().removeSuffix(":") }
        names.forEach { name ->
            withClue(name) { schemaBlock(lines, name).none { it.trim() == "genderDescription:" } shouldBe true }
        }
    }

    // --- Apoio ----------------------------------------------------------------------------------------------

    private fun otherProfile(description: String?): Map<String, Any?> = profileWith("OTHER", description)

    private fun profileWith(
        gender: String,
        description: String?,
    ): Map<String, Any?> = guests.profileBody(extra = mapOf("gender" to gender, "genderDescription" to description))

    private fun create(
        gender: String,
        description: String?,
    ): UUID =
        UUID.fromString(
            guests
                .createGuest(
                    guests.createBody(profile = profileWith(gender, description), documents = emptyList()),
                )["id"]
                .asString(),
        )

    private fun call(
        method: org.springframework.http.HttpMethod,
        path: String,
        body: Any? = null,
        ifMatch: String? = null,
    ): MockHttpServletResponse = iam.call(method, path, iam.asAdmin(), body, ifMatch)

    private fun MockHttpServletResponse.problem(
        status: Int,
        code: String,
    ): JsonNode = with(iam) { this@problem.problem(status, code) }

    private fun MockHttpServletResponse.ok(): JsonNode = with(iam) { this@ok.ok() }

    private fun raw(
        method: org.springframework.http.HttpMethod,
        path: String,
        content: String,
        ifMatch: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .request(method, path) {
                with(iam.asAdmin())
                if (ifMatch != null) header("If-Match", ifMatch)
                contentType = MediaType.APPLICATION_JSON
                this.content = content
            }.andReturn()
            .response

    private fun merge(
        survivor: UUID,
        absorbed: UUID,
        take: List<String> = emptyList(),
    ): MockHttpServletResponse =
        iam.call(
            POST,
            "/api/v1/guests/$survivor/merge",
            iam.asAdmin(),
            mapOf("mergedGuestId" to absorbed, "takeFromMerged" to take),
            ifMatch = guests.etagOf("/api/v1/guests/$survivor"),
        )

    private fun stored(id: UUID): String? =
        jdbc
            .sql("SELECT gender_description FROM guests.guest WHERE id = :id")
            .param("id", id)
            .query(String::class.java)
            .optional()
            .orElse(null)

    private fun auditRows(
        id: UUID,
        action: String,
    ): List<Map<String, Any?>> =
        jdbc
            .sql(
                "SELECT CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state " +
                    "FROM audit.audit_entry WHERE entity_id = :id AND action = :action ORDER BY occurred_at",
            ).param("id", id.toString())
            .param("action", action)
            .query()
            .listOfRows()

    /** Todo o texto de estado de todas as linhas de auditoria do perfil. */
    private fun auditText(id: UUID): String =
        jdbc
            .sql(
                "SELECT CAST(before_state AS text) || ' ' || CAST(after_state AS text) FROM audit.audit_entry " +
                    "WHERE entity_id = :id",
            ).param("id", id.toString())
            .query(String::class.java)
            .list()
            .joinToString("\n")

    private fun schemaBlock(
        lines: List<String>,
        name: String,
    ): List<String> {
        val start = lines.indexOfFirst { it == "    $name:" }
        withClue("esquema $name") { (start >= 0) shouldBe true }
        val rest = lines.drop(start + 1)
        return rest.takeWhile { it.isBlank() || it.startsWith("      ") }
    }

    private fun propertyBlock(
        schema: List<String>,
        property: String,
    ): String {
        val start = schema.indexOfFirst { it == "        $property:" }
        withClue("propriedade $property") { (start >= 0) shouldBe true }
        return schema
            .drop(start + 1)
            .takeWhile { it.startsWith("          ") }
            .joinToString("\n")
    }
}
