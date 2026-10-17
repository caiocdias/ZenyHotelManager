package com.zeny.hotel.guests

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
import java.security.SecureRandom
import java.util.UUID

/**
 * Apoio dos testes do `guests`: monta corpos, gera CPF e CNPJ válidos e únicos (o banco é compartilhado) e cadastra
 * perfis e empresas pela API como o administrador.
 */
class GuestsTestSupport(
    private val iam: IamTestSupport,
) {
    private val admin: RequestPostProcessor get() = iam.asAdmin()

    fun suffix(length: Int = SUFFIX_LENGTH): String = UuidV7.generate().toString().takeLast(length)

    /** Um nome único com acento, para provar a busca sem acento. */
    fun uniqueName(prefix: String = "José"): String = "$prefix Conceição ${suffix()}"

    fun uniqueEmail(): String = "hospede.${suffix()}@exemplo.com.br"

    @Suppress("LongParameterList") // os campos que os cenários variam, com padrões
    fun profileBody(
        fullName: String = uniqueName(),
        email: String? = uniqueEmail(),
        residenceCountry: String? = "BR",
        address: Map<String, Any?>? = address(),
        companyId: UUID? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        mapOf(
            "fullName" to fullName,
            "socialName" to null,
            "nationality" to "BR",
            "birthDate" to "1990-05-20",
            "gender" to "FEMALE",
            "email" to email,
            "phone" to "+55 11 98765-4321",
            "residenceCountry" to residenceCountry,
            "address" to address,
            "preferences" to mapOf("floor" to "Alto", "bed" to "Casal", "pillow" to "Baixo"),
            "vip" to false,
            "notes" to "Prefere quarto silencioso",
            "companyId" to companyId,
        ) + extra

    fun address(): Map<String, Any?> =
        mapOf(
            "street" to "Rua das Acácias",
            "number" to "742",
            "complement" to "Apto 12",
            "district" to "Jardim Paulista",
            "city" to "São Paulo",
            "state" to "SP",
            "postalCode" to "01407-000",
            "ibgeCityCode" to "3550308",
        )

    fun document(
        type: String,
        number: String,
        issuingCountry: String? = null,
    ): Map<String, Any?> = mapOf("type" to type, "issuingCountry" to issuingCountry, "number" to number)

    fun createBody(
        profile: Map<String, Any?> = profileBody(),
        documents: List<Map<String, Any?>> = listOf(document("CPF", formattedCpf(newCpf()))),
        sensitiveData: Map<String, Any?>? = null,
    ): Map<String, Any?> = mapOf("profile" to profile, "documents" to documents, "sensitiveData" to sensitiveData)

    fun createGuest(body: Map<String, Any?> = createBody()): JsonNode =
        with(iam) { iam.call(POST, "/api/v1/guests", admin, body).expectStatus(201).shouldNotBeNull() }

    fun companyBody(
        cnpj: String = newCnpj(),
        legalName: String = "Viagens Ômega ${suffix()} Ltda",
    ): Map<String, Any?> =
        mapOf(
            "legalName" to legalName,
            "tradeName" to "Ômega Turismo",
            "cnpj" to cnpj,
            "stateRegistration" to "123456789",
            "municipalRegistration" to "98765",
            "address" to address(),
            "contactName" to "Carla Gerente",
            "phone" to "(11) 3333-4444",
            "email" to "contato.${suffix()}@omega.com.br",
            "billingEmail" to "financeiro@omega.com.br",
            "creditLimit" to "5000.00",
        )

    fun createCompany(body: Map<String, Any?> = companyBody()): UUID =
        with(iam) {
            UUID.fromString(iam.call(POST, "/api/v1/companies", admin, body).expectStatus(201)!!["id"].asString())
        }

    fun etagOf(path: String): String {
        val response: MockHttpServletResponse = iam.call(GET, path, admin)
        withClue(response.getContentAsString(Charsets.UTF_8)) { response.status shouldBe 200 }
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    companion object {
        private const val SUFFIX_LENGTH = 12
        private val random = SecureRandom()

        fun definition(code: PermissionCode) = GuestsPermissions.permissions.single { it.code == code }

        /** Um CPF válido sorteado (só os dígitos). */
        fun newCpf(): String {
            val base = List(9) { random.nextInt(10) }
            val first = cpfDigit(base)
            val second = cpfDigit(base + first)
            return (base + first + second).joinToString("")
        }

        fun formattedCpf(digits: String) =
            "${digits.substring(0, 3)}.${digits.substring(3, 6)}.${digits.substring(6, 9)}-${digits.substring(9)}"

        /** Um CNPJ numérico válido sorteado. */
        fun newCnpj(): String {
            val base = List(12) { random.nextInt(10) }
            val first = cnpjDigit(base, listOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
            val second = cnpjDigit(base + first, listOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2))
            return (base + first + second).joinToString("")
        }

        private fun cpfDigit(values: List<Int>): Int {
            val sum = values.mapIndexed { index, value -> value * (values.size + 1 - index) }.sum()
            return (sum * 10 % 11) % 10
        }

        private fun cnpjDigit(
            values: List<Int>,
            weights: List<Int>,
        ): Int {
            val remainder = values.zip(weights).sumOf { (value, weight) -> value * weight } % 11
            return if (remainder < 2) 0 else 11 - remainder
        }
    }
}
