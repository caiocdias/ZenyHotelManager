package com.zeny.hotel.housekeeping.domain

import com.zeny.hotel.housekeeping.CleaningTypeId
import com.zeny.hotel.housekeeping.HousekeepingErrorCodes
import com.zeny.hotel.housekeeping.ScheduledCleaningId
import com.zeny.hotel.rooms.AreaId
import com.zeny.hotel.rooms.RoomId
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ErrorCodes
import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import com.zeny.hotel.shared.InvalidInputException
import com.zeny.hotel.shared.StorableRange
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate

/** Os dados de criação de uma faxina programada: o local (a UH **ou** a área), o tipo, a frequência e a última. */
data class ScheduledCleaningInput(
    val roomId: RoomId?,
    val areaId: AreaId?,
    val cleaningTypeId: CleaningTypeId,
    val frequencyDays: Int,
    val lastDoneOn: LocalDate?,
)

/**
 * Faxina programada (RF-GOV-10, SPEC §8.2.10): um tipo de limpeza repetido a cada [frequencyDays] dias (de 1 a 365)
 * numa UH ou numa área comum (exatamente um local, que não muda). A próxima data é `lastDoneOn + frequencyDays` ou,
 * sem a última, a data operacional (D7); a edição troca só o tipo e a frequência e a recalcula. A reativação mantém a
 * próxima data antiga, mesmo vencida (a E02 decide se a recalcula). [recordDone] registra uma faxina feita (E04) e
 * nunca faz o `lastDoneOn` voltar. As referências (local, tipo) e o tipo ativo são do caso de uso. Nunca é apagada (o
 * banco recusa), só desativada.
 */
@Entity
@Table(name = "scheduled_cleaning", schema = "housekeeping")
@Suppress("LongParameterList") // os campos da faxina, só pelo create
class ScheduledCleaning private constructor(
    @Id
    val id: ScheduledCleaningId,
    @Column(name = "room_id", updatable = false)
    val roomId: RoomId?,
    @Column(name = "area_id", updatable = false)
    val areaId: AreaId?,
    cleaningTypeId: CleaningTypeId,
    frequencyDays: Int,
    lastDoneOn: LocalDate?,
    nextDueOn: LocalDate,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Column(name = "cleaning_type_id", nullable = false)
    var cleaningTypeId: CleaningTypeId = cleaningTypeId
        protected set

    @Column(name = "frequency_days", nullable = false)
    var frequencyDays: Int = frequencyDays
        protected set

    @Column(name = "last_done_on")
    var lastDoneOn: LocalDate? = lastDoneOn
        protected set

    @Column(name = "next_due_on", nullable = false)
    var nextDueOn: LocalDate = nextDueOn
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

    /**
     * Troca o tipo e a frequência e recalcula a próxima data com [businessDate] (D7); devolve se algo mudou. O tipo
     * novo (existente e ativo) é do caso de uso.
     */
    fun update(
        cleaningTypeId: CleaningTypeId,
        frequencyDays: Int,
        businessDate: LocalDate,
        now: Instant,
    ): Boolean {
        requireFrequency(frequencyDays)
        if (cleaningTypeId == this.cleaningTypeId && frequencyDays == this.frequencyDays) return false
        this.cleaningTypeId = cleaningTypeId
        this.frequencyDays = frequencyDays
        nextDueOn = nextDueOn(lastDoneOn, frequencyDays, businessDate)
        updatedAt = now
        return true
    }

    /**
     * Registra a faxina feita em [date] (a E04 chama ao concluir a tarefa): o `lastDoneOn` só avança, e a próxima data
     * passa a `date + frequencyDays`. Uma data anterior ou igual à última não muda nada; devolve se mudou.
     */
    fun recordDone(date: LocalDate): Boolean {
        if (lastDoneOn?.let { !date.isAfter(it) } == true) return false
        lastDoneOn = date
        nextDueOn = date.plusDays(frequencyDays.toLong())
        return true
    }

    /** Desativa; devolve se mudou. */
    fun deactivate(now: Instant): Boolean {
        if (!active) return false
        active = false
        updatedAt = now
        return true
    }

    /**
     * Reativa, com a próxima data antiga (mesmo vencida); devolve se mudou. O tipo e o local ativos são do caso de uso.
     */
    fun reactivate(now: Instant): Boolean {
        if (active) return false
        active = true
        updatedAt = now
        return true
    }

    override fun toString(): String = "ScheduledCleaning(id=$id, cleaningTypeId=$cleaningTypeId)"

    companion object {
        val FREQUENCY_DAYS = 1..365

        /**
         * A forma de [input], sem consultar nada: exatamente um local (`400 SCHEDULED_CLEANING_LOCATION_INVALID`), a
         * frequência de 1 a 365 (`400 HOUSEKEEPING_DATA_INVALID`) e o `lastDoneOn` a partir do piso do banco
         * ([StorableRange.MIN_DATE]; abaixo dele o pgjdbc gravaria `-infinity`): `400 VALIDATION_FAILED`, com
         * `INVALID_VALUE` no campo.
         */
        fun validate(input: ScheduledCleaningInput) {
            if ((input.roomId == null) == (input.areaId == null)) {
                val message = "Informe o local da faxina: a UH (roomId) ou a área (areaId), um só."
                val code = HousekeepingErrorCodes.SCHEDULED_CLEANING_LOCATION_INVALID
                throw InvalidInputException(
                    code,
                    message,
                    listOf("roomId", "areaId").map { FieldViolation(it, code.value, message) },
                )
            }
            requireFrequency(input.frequencyDays)
            if (input.lastDoneOn != null && !StorableRange.contains(input.lastDoneOn)) {
                val message = "Informe a última faxina a partir de ${StorableRange.MIN_DATE}."
                throw InvalidInputException(
                    ErrorCodes.VALIDATION_FAILED,
                    message,
                    listOf(FieldViolation("lastDoneOn", FieldViolationCodes.INVALID_VALUE, message)),
                )
            }
        }

        /**
         * Uma faxina programada nova e ativa ([validate]); a última faxina depois de [businessDate] é `422
         * SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE`.
         */
        fun create(
            input: ScheduledCleaningInput,
            businessDate: LocalDate,
            now: Instant,
        ): ScheduledCleaning {
            validate(input)
            if (input.lastDoneOn?.isAfter(businessDate) == true) {
                throw BusinessRuleException(
                    HousekeepingErrorCodes.SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE,
                    "A última faxina não pode ser depois da data operacional ($businessDate).",
                )
            }
            return ScheduledCleaning(
                ScheduledCleaningId.new(),
                input.roomId,
                input.areaId,
                input.cleaningTypeId,
                input.frequencyDays,
                input.lastDoneOn,
                nextDueOn(input.lastDoneOn, input.frequencyDays, businessDate),
                now,
            )
        }

        private fun requireFrequency(frequencyDays: Int) =
            HousekeepingRules.inRange("frequencyDays", frequencyDays, FREQUENCY_DAYS)

        private fun nextDueOn(
            lastDoneOn: LocalDate?,
            frequencyDays: Int,
            businessDate: LocalDate,
        ): LocalDate = lastDoneOn?.plusDays(frequencyDays.toLong()) ?: businessDate
    }
}
