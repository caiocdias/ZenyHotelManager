package com.zeny.hotel.rooms

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
import org.springframework.test.web.servlet.request.RequestPostProcessor
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Apoio dos testes do `rooms`: cria características, tipos, UHs e áreas pela API, como o administrador. */
class RoomsTestSupport(
    private val iam: IamTestSupport,
) {
    private val admin: RequestPostProcessor get() = iam.asAdmin()

    /** Um sufixo aleatório de [length] caracteres hexadecimais (o fim de um UUIDv7 é aleatório). */
    fun suffix(length: Int = SUFFIX_LENGTH): String = UuidV7.generate().toString().takeLast(length)

    fun uniqueCode(prefix: String = "T"): String = (prefix + suffix()).uppercase()

    fun uniqueNumber(): String = suffix(NUMBER_LENGTH)

    fun uniqueName(prefix: String): String = "$prefix ${suffix()}"

    fun createFeature(
        code: String = uniqueCode("F"),
        name: String = uniqueName("Vista"),
    ): UUID = id(post("/api/v1/room-features", mapOf("code" to code, "name" to name)))

    @Suppress("LongParameterList") // os campos do tipo, com padrões
    fun roomTypeBody(
        code: String = uniqueCode(),
        name: String = uniqueName("Standard"),
        standardAdults: Int = 2,
        standardChildren: Int = 0,
        maxAdults: Int = 3,
        maxChildren: Int = 1,
        featureIds: List<UUID> = emptyList(),
        displayOrder: Int = 10,
    ): Map<String, Any?> =
        mapOf(
            "code" to code,
            "name" to name,
            "description" to "Quarto com varanda",
            "standardAdults" to standardAdults,
            "standardChildren" to standardChildren,
            "maxAdults" to maxAdults,
            "maxChildren" to maxChildren,
            "bedSetup" to "1 casal + 1 solteiro",
            "featureIds" to featureIds,
            "displayOrder" to displayOrder,
        )

    fun createRoomType(body: Map<String, Any?> = roomTypeBody()): UUID = id(post("/api/v1/room-types", body))

    fun roomBody(
        roomTypeId: UUID,
        number: String = uniqueNumber(),
        featureIds: List<UUID> = emptyList(),
        floor: String? = "1º andar",
    ): Map<String, Any?> =
        mapOf(
            "number" to number,
            "floor" to floor,
            "roomTypeId" to roomTypeId,
            "featureIds" to featureIds,
            "notes" to "Perto do elevador",
        )

    fun createRoom(
        roomTypeId: UUID,
        number: String = uniqueNumber(),
        featureIds: List<UUID> = emptyList(),
    ): UUID = id(post("/api/v1/rooms", roomBody(roomTypeId, number, featureIds)))

    fun areaBody(
        name: String = uniqueName("Piscina"),
        kind: AreaKind = AreaKind.POOL,
    ): Map<String, Any?> = mapOf("name" to name, "kind" to kind.name, "description" to "Área externa")

    fun createArea(
        name: String = uniqueName("Piscina"),
        kind: AreaKind = AreaKind.POOL,
    ): UUID = id(post("/api/v1/areas", areaBody(name, kind)))

    /** Todos os itens da listagem [path] (com a query, se houver), seguindo as páginas de [limit] itens. */
    fun listAll(
        path: String,
        auth: RequestPostProcessor = admin,
        limit: Int = 200,
    ): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        val separator = if ('?' in path) "&" else "?"
        var cursor: String? = null
        var pages = 0
        do {
            val page =
                with(iam) {
                    call(GET, "$path${separator}limit=$limit" + cursor?.let { "&cursor=$it" }.orEmpty(), auth).ok()
                }
            items += page["items"].values()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asString()
            pages++
            withClue("paginação sem fim em $path") { (pages < MAX_PAGES) shouldBe true }
        } while (cursor != null)
        return items
    }

    fun etagOf(path: String): String {
        val response: MockHttpServletResponse = iam.call(GET, path, admin)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    private fun post(
        path: String,
        body: Any,
    ): MockHttpServletResponse = iam.call(POST, path, admin, body)

    private fun id(response: MockHttpServletResponse): UUID =
        with(iam) { UUID.fromString(response.expectStatus(201)!!["id"].asString()) }

    companion object {
        private const val SUFFIX_LENGTH = 12
        private const val NUMBER_LENGTH = 8
        private const val MAX_PAGES = 1000

        fun definition(code: PermissionCode) = RoomsPermissions.permissions.single { it.code == code }
    }
}
