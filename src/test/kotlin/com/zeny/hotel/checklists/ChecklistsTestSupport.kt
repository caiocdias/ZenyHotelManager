package com.zeny.hotel.checklists

import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.UuidV7
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/** Apoio dos testes do `checklists`: corpos de modelo e chamadas à API como o administrador. */
class ChecklistsTestSupport(
    private val iam: IamTestSupport,
) {
    fun uniqueName(prefix: String = "Modelo"): String =
        "$prefix ${UuidV7.generate().toString().takeLast(SUFFIX_LENGTH)}"

    fun item(
        key: String,
        label: String = key.uppercase(),
        type: ChecklistItemType = ChecklistItemType.CHECK,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf("key" to key, "label" to label, "type" to type.name) + extra

    fun section(
        title: String,
        vararg items: Map<String, Any?>,
    ): Map<String, Any?> = mapOf("title" to title, "items" to items.toList())

    /** Um modelo com uma seção de dois itens de marcação (`bed`, `towels`) e as seções extras. */
    fun templateBody(
        name: String = uniqueName(),
        context: ChecklistContext? = ChecklistContext.HOUSEKEEPING,
        applicability: List<Map<String, Any?>> = emptyList(),
        sections: List<Map<String, Any?>> =
            listOf(section("Quarto", item("bed", "Cama feita"), item("towels", "Toalhas"))),
    ): Map<String, Any?> =
        buildMap {
            put("name", name)
            if (context != null) put("context", context.name)
            put("applicability", applicability)
            put("sections", sections)
        }

    fun create(body: Map<String, Any?> = templateBody()): UUID =
        with(iam) {
            UUID.fromString(
                call(POST, "/api/v1/checklist-templates", asAdmin(), body).expectStatus(201)!!["id"].asString(),
            )
        }

    fun etagOf(id: UUID): String {
        val response: MockHttpServletResponse = iam.call(GET, "/api/v1/checklist-templates/$id", iam.asAdmin())
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    /** Publica o rascunho atual e devolve o número da versão. */
    fun publish(id: UUID): Int =
        with(iam) {
            call(POST, "/api/v1/checklist-templates/$id/publish", asAdmin(), ifMatch = etagOf(id))
                .expectStatus(201)!!["number"]
                .asInt()
        }

    companion object {
        private const val SUFFIX_LENGTH = 12

        fun definition(code: PermissionCode) = ChecklistsPermissions.permissions.single { it.code == code }
    }
}
