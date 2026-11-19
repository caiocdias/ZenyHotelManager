package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.CleaningTypeKey
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.rooms.RoomTypeId
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.InvalidInputException
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import java.time.Instant
import java.util.UUID

/** Os créditos de um tipo de limpeza para um tipo de UH (o esforço da limpeza, que a distribuição balanceia). */
data class CleaningCredit(
    val roomTypeId: RoomTypeId,
    val credits: Int,
)

/** Os dados editáveis de um tipo de limpeza (a [CleaningTypeKey] não é editável). */
data class CleaningTypeDetails(
    val code: String,
    val name: String,
    val requiresInspection: Boolean,
    val defaultPriority: Int,
    val linenChangeEveryNights: Int?,
    val checklistTemplateId: UUID?,
    val credits: List<CleaningCredit>,
)

/**
 * Tipo de limpeza (RF-GOV-01, SPEC §8.2.10): código único em maiúsculas, nome, créditos por tipo de UH, se exige
 * inspeção, prioridade padrão (de 1 a 5) e a regra de troca de enxoval (a cada N noites, de 1 a 60; nula = sem regra
 * por noites, troca só a pedido). O vínculo opcional ao modelo de checklist ([checklistTemplateId]) é conferido pelo
 * caso de uso. Os tipos de UH dos créditos são conferidos pelo caso de uso, pela `RoomsApi`; crédito para tipo de UH
 * desativado é aceito (D6). Os seis tipos da carga inicial têm a [key], que nunca muda (D5); os criados pela API, não.
 * Nunca é apagado (o banco recusa), só desativado.
 */
@Entity
@Table(name = "cleaning_type", schema = "housekeeping")
class CleaningType private constructor(
    @Id
    val id: CleaningTypeId,
    details: CleaningTypeDetails,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Enumerated(EnumType.STRING)
    @Column(name = "system_key", updatable = false)
    val key: CleaningTypeKey? = null

    @Column(nullable = false)
    var code: String = ""
        protected set

    @Column(nullable = false)
    var name: String = ""
        protected set

    @Column(name = "requires_inspection", nullable = false)
    var requiresInspection: Boolean = false
        protected set

    @Column(name = "default_priority", nullable = false)
    var defaultPriority: Int = 0
        protected set

    @Column(name = "linen_change_every_nights")
    var linenChangeEveryNights: Int? = null
        protected set

    /** O modelo de checklist vinculado (id opaco do `checklists`, sem FK entre schemas). */
    @Column(name = "checklist_template_id")
    var checklistTemplateId: UUID? = null
        protected set

    @Column(nullable = false)
    var active: Boolean = true
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    var version: Long? = null
        protected set

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "cleaning_type_credit",
        schema = "housekeeping",
        joinColumns = [JoinColumn(name = "cleaning_type_id")],
    )
    @MapKeyColumn(name = "room_type_id")
    @Column(name = "credits", nullable = false)
    @BatchSize(size = BATCH)
    private val creditMap: MutableMap<UUID, Int> = mutableMapOf()

    init {
        apply(details)
    }

    /** Os créditos, em ordem de tipo de UH. */
    val credits: List<CleaningCredit>
        get() = creditMap.entries.sortedBy { it.key }.map { CleaningCredit(RoomTypeId(it.key), it.value) }

    val details: CleaningTypeDetails
        get() =
            CleaningTypeDetails(
                code,
                name,
                requiresInspection,
                defaultPriority,
                linenChangeEveryNights,
                checklistTemplateId,
                credits,
            )

    /** Troca os dados editáveis (os créditos inteiros); devolve se algo mudou. As referências são do caso de uso. */
    fun update(
        details: CleaningTypeDetails,
        now: Instant,
    ): Boolean {
        val changed = apply(details)
        if (changed) updatedAt = now
        return changed
    }

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /** Reativa; devolve se mudou. */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    private fun apply(details: CleaningTypeDetails): Boolean {
        val valid = validate(details)
        if (valid == this.details) return false
        code = valid.code
        name = valid.name
        requiresInspection = valid.requiresInspection
        defaultPriority = valid.defaultPriority
        linenChangeEveryNights = valid.linenChangeEveryNights
        checklistTemplateId = valid.checklistTemplateId
        val wanted = valid.credits.associate { it.roomTypeId.value to it.credits }
        creditMap.keys.retainAll(wanted.keys)
        wanted.forEach { (roomType, credits) -> if (creditMap[roomType] != credits) creditMap[roomType] = credits }
        return true
    }

    override fun toString(): String = "CleaningType(id=$id, code=$code)"

    companion object {
        const val MAX_NAME_LENGTH = 80
        const val MAX_CREDITS = 200
        val PRIORITIES = 1..5
        val LINEN_CHANGE_NIGHTS = 1..60
        val CREDIT_RANGE = 1..100
        private const val BATCH = 100
        private const val LINEN_FIELD = "linenChangeEveryNights"

        fun create(
            details: CleaningTypeDetails,
            now: Instant,
        ): CleaningType = CleaningType(CleaningTypeId.new(), details, now)

        /**
         * Os dados válidos (código em maiúsculas, nome sem espaços nas pontas e créditos em ordem de tipo de UH); senão
         * `400` com o campo: `HOUSEKEEPING_DATA_INVALID` fora do formato ou da faixa, `CLEANING_TYPE_CREDIT_DUPLICATED`
         * com o mesmo tipo de UH duas vezes.
         */
        fun validate(details: CleaningTypeDetails): CleaningTypeDetails {
            HousekeepingRules.inRange("defaultPriority", details.defaultPriority, PRIORITIES)
            details.linenChangeEveryNights?.let { HousekeepingRules.inRange(LINEN_FIELD, it, LINEN_CHANGE_NIGHTS) }
            HousekeepingRules.require(
                details.credits.size <= MAX_CREDITS,
                "credits",
                "Informe até $MAX_CREDITS créditos.",
            )
            details.credits.forEachIndexed { index, credit ->
                HousekeepingRules.inRange("credits[$index].credits", credit.credits, CREDIT_RANGE)
            }
            val roomTypes = details.credits.map { it.roomTypeId }
            if (roomTypes.distinct().size < roomTypes.size) {
                throw HousekeepingRules.invalid(
                    HousekeepingErrorCodes.CLEANING_TYPE_CREDIT_DUPLICATED,
                    "credits",
                    "Informe cada tipo de UH uma vez só nos créditos.",
                )
            }
            return details.copy(
                code = HousekeepingRules.code(details.code),
                name = HousekeepingRules.name("name", details.name, MAX_NAME_LENGTH),
                credits = details.credits.sortedBy { it.roomTypeId.value },
            )
        }
    }
}

/** Validações comuns do `housekeeping`; as recusas saem como `400` com o campo em `errors[]`. */
internal object HousekeepingRules {
    private val CODE = Regex("[A-Z0-9][A-Z0-9_-]{0,19}")
    private const val NUL = '\u0000'

    /** Código: sem espaços nas pontas, em maiúsculas, letras, dígitos, `_` e `-`, até 20. */
    fun code(value: String): String {
        val normalized = value.trim().uppercase()
        require(CODE.matches(normalized), "code", "Informe o código com até 20 letras, dígitos, \"_\" ou \"-\".")
        return normalized
    }

    /** Nome obrigatório, sem espaços nas pontas, de 1 a [max] caracteres e sem NUL (que o PostgreSQL não guarda). */
    fun name(
        field: String,
        value: String,
        max: Int,
    ): String {
        val trimmed = value.trim()
        require(
            trimmed.isNotEmpty() && trimmed.length <= max && NUL !in trimmed,
            field,
            "Informe $field com até $max caracteres.",
        )
        return trimmed
    }

    /**
     * Texto livre obrigatório de uma ocorrência (achado ou dano, E06), aparado, de 1 a [max] caracteres (code points),
     * sem NUL; a recusa não ecoa o texto.
     */
    fun freeText(
        field: String,
        raw: String?,
        max: Int,
    ): String =
        optionalFreeText(field, raw, max)
            ?: throw invalid(
                HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID,
                field,
                "Informe $field com até $max caracteres.",
            )

    /** O texto livre opcional aparado, ou `null` se vazio; com mais de [max] caracteres ou com NUL, `400`. */
    fun optionalFreeText(
        field: String,
        raw: String?,
        max: Int,
    ): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require(
            trimmed.codePointCount(0, trimmed.length) <= max && NUL !in trimmed,
            field,
            "Informe $field com até $max caracteres.",
        )
        return trimmed
    }

    /** O local de uma ocorrência (E06): a UH **ou** a área, um só; os dois ou nenhum, `400 …_LOCATION_INVALID`. */
    fun requireOneLocation(
        roomId: RoomId?,
        areaId: AreaId?,
    ) {
        if ((roomId == null) != (areaId == null)) return
        val code = HousekeepingErrorCodes.HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID
        val message = "Informe o local: a UH (roomId) ou a área (areaId), um só."
        throw InvalidInputException(
            code,
            message,
            listOf("roomId", "areaId").map { FieldViolation(it, code.value, message) },
        )
    }

    fun inRange(
        field: String,
        value: Int,
        range: IntRange,
    ) = require(value in range, field, "Informe $field de ${range.first} a ${range.last}.")

    fun require(
        condition: Boolean,
        field: String,
        message: String,
    ) {
        if (!condition) throw invalid(HousekeepingErrorCodes.HOUSEKEEPING_DATA_INVALID, field, message)
    }

    fun invalid(
        code: ErrorCode,
        field: String,
        message: String,
    ) = InvalidInputException(code, message, listOf(FieldViolation(field, code.value, message)))
}
