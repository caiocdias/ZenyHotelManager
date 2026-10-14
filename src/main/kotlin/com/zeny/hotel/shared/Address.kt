package com.zeny.hotel.shared

import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

/**
 * Endereço no Brasil, com o código IBGE do município (7 dígitos, os 2 primeiros da UF), que
 * a FNRH e os documentos fiscais exigem. O CEP é guardado só com os 8 dígitos.
 *
 * Confere a forma do código IBGE e se ele pertence à UF; se o município existe é com quem
 * tem a lista oficial (validação da FNRH, M5). Endereço no exterior não cabe aqui.
 *
 * É `@Embeddable`: numa entidade, `@Embedded val address: Address` gera as colunas `street`,
 * `number`, `complement`, `district`, `city`, `state`, `postal_code` e `ibge_city_code`
 * (renomeie com `@AttributeOverride` se precisar).
 */
@Embeddable
@Suppress("LongParameterList") // cada campo é parte do valor; um builder só esconderia isso
class Address(
    val street: String,
    val number: String,
    val complement: String?,
    val district: String,
    val city: String,
    @Enumerated(EnumType.STRING)
    val state: BrazilianState,
    postalCode: String,
    val ibgeCityCode: String,
) {
    /** CEP com 8 dígitos, sem hífen. */
    val postalCode: String = postalCode.trim().replace("-", "")

    init {
        require(street.isNotBlank()) { "Logradouro obrigatório" }
        require(number.isNotBlank()) { "Número obrigatório (use \"S/N\" se não houver)" }
        require(district.isNotBlank()) { "Bairro obrigatório" }
        require(city.isNotBlank()) { "Município obrigatório" }
        require(POSTAL_CODE.matches(this.postalCode)) { "CEP inválido: '$postalCode'" }
        require(IBGE_CITY_CODE.matches(ibgeCityCode)) { "Código IBGE do município inválido: '$ibgeCityCode'" }
        require(ibgeCityCode.startsWith(state.ibgeCode)) {
            "O código IBGE $ibgeCityCode não pertence à UF ${state.name}"
        }
    }

    /** `01310-100`. */
    fun formattedPostalCode(): String = applyMask(postalCode, "#####-###")

    private fun fields(): List<Any?> =
        listOf(street, number, complement, district, city, state, postalCode, ibgeCityCode)

    override fun equals(other: Any?): Boolean = other is Address && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "Address(street=$street, number=$number, complement=$complement, district=$district, city=$city, " +
            "state=$state, postalCode=$postalCode, ibgeCityCode=$ibgeCityCode)"

    private companion object {
        val POSTAL_CODE = Regex("""\d{8}""")
        val IBGE_CITY_CODE = Regex("""\d{7}""")
    }
}

/** As 27 unidades da federação, pela sigla, com o código IBGE de cada uma. */
@Suppress("MagicNumber") // os códigos IBGE das UFs são a própria tabela
enum class BrazilianState(
    private val code: Int,
) {
    RO(11),
    AC(12),
    AM(13),
    RR(14),
    PA(15),
    AP(16),
    TO(17),
    MA(21),
    PI(22),
    CE(23),
    RN(24),
    PB(25),
    PE(26),
    AL(27),
    SE(28),
    BA(29),
    MG(31),
    ES(32),
    RJ(33),
    SP(35),
    PR(41),
    SC(42),
    RS(43),
    MS(50),
    MT(51),
    GO(52),
    DF(53),
    ;

    /** Os 2 dígitos da UF no código IBGE (`SP` → `"35"`). */
    val ibgeCode: String get() = code.toString()
}
