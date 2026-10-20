package com.zeny.hotel.rates.domain

import com.zeny.hotel.rates.PackageId
import com.zeny.hotel.rates.RatesErrorCodes
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.TypedIdConverter
import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Version
import java.time.Instant

/**
 * O que todo registro do `rates` tem: criação, última alteração, versão (lock otimista, `ETag`) e ativo/inativo. Nada é
 * apagado (o banco recusa): desativar tira o registro de uso e guarda o histórico.
 */
@MappedSuperclass
@Suppress("AbstractClassCanBeConcreteClass") // superclasse mapeada do JPA: só existe nas entidades que a estendem
abstract class RatesRecord(
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    /** Desativa; devolve se mudou. As condições (não estar em uso) são do caso de uso. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa; devolve se mudou. As condições (políticas e pacotes ativos) são do caso de uso. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    /** Marca a alteração quando [changed]; devolve [changed]. */
    protected fun touched(
        changed: Boolean,
        now: Instant,
    ): Boolean {
        if (changed) updatedAt = now
        return changed
    }
}

/** Conversor do [RoomTypeId] para os tipos de UH do plano. */
class RoomTypeIdConverter : TypedIdConverter<RoomTypeId>(::RoomTypeId)

/** Conversor do [PackageId] para os pacotes do plano. */
class PackageIdConverter : TypedIdConverter<PackageId>(::PackageId)

/** Validações comuns do `rates`; as recusas saem como `400` com o campo em `errors[]`. */
internal object RatesRules {
    private val CODE = Regex("[A-Z0-9][A-Z0-9_-]{0,19}")

    /** Código de plano ou pacote: sem espaços nas pontas, em maiúsculas, letras, dígitos, `_` e `-`, até 20. */
    fun code(value: String): String {
        val normalized = value.trim().uppercase()
        if (!CODE.matches(normalized)) {
            throw invalid(
                RatesErrorCodes.RATES_DATA_INVALID,
                "code",
                "Informe o código com até 20 letras, dígitos, \"_\" ou \"-\".",
            )
        }
        return normalized
    }

    /** Texto obrigatório, sem espaços nas pontas, de 1 a [max] caracteres. */
    fun required(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > max) {
            throw invalid(RatesErrorCodes.RATES_DATA_INVALID, field, "Informe $field com até $max caracteres.")
        }
        return trimmed
    }

    /** Texto opcional: em branco vira `null`; senão, até [max] caracteres. */
    fun optional(
        field: String,
        value: String?,
        max: Int,
    ): String? {
        val trimmed = value?.trim()?.ifEmpty { null } ?: return null
        if (trimmed.length > max) {
            throw invalid(RatesErrorCodes.RATES_DATA_INVALID, field, "Use até $max caracteres em $field.")
        }
        return trimmed
    }

    /** Um conjunto com de [min] a [max] itens. */
    fun <T> sized(
        field: String,
        values: Set<T>,
        min: Int,
        max: Int,
    ): Set<T> {
        if (values.size !in min..max) {
            throw invalid(RatesErrorCodes.RATES_DATA_INVALID, field, "Informe de $min a $max itens em $field.")
        }
        return values
    }

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}

/** Troca o conteúdo de [target] por [values]; devolve se mudou. */
internal fun <T> replaceAll(
    target: MutableSet<T>,
    values: Set<T>,
): Boolean {
    if (target == values) return false
    target.retainAll(values)
    target.addAll(values)
    return true
}
