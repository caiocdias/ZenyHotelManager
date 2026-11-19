package com.zeny.hotel.housekeeping

import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.TypedId
import com.zeny.hotel.shared.UuidV7
import java.util.UUID

/** Id de um tipo de limpeza. */
@JvmInline
value class CleaningTypeId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CleaningTypeId = CleaningTypeId(UuidV7.generate())
    }
}

/** Id de uma faxina programada. */
@JvmInline
value class ScheduledCleaningId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ScheduledCleaningId = ScheduledCleaningId(UuidV7.generate())
    }
}

/** Id de uma tarefa de governança. */
@JvmInline
value class HousekeepingTaskId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): HousekeepingTaskId = HousekeepingTaskId(UuidV7.generate())
    }
}

/** Id de uma inspeção de UH (M6 E05). */
@JvmInline
value class InspectionId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): InspectionId = InspectionId(UuidV7.generate())
    }
}

/**
 * Status de uma inspeção (D2-E05): `IN_PROGRESS`, aberta pelo início; `COMPLETED`, concluída com o resultado. O
 * conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class InspectionStatus {
    IN_PROGRESS,
    COMPLETED,
}

/**
 * Resultado de uma inspeção (D2-E05): `APPROVED`, a UH fica Inspecionada; `REJECTED`, a limpeza volta à camareira. O
 * conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class InspectionResult {
    APPROVED,
    REJECTED,
}

/** Id de um item de achados e perdidos (M6 E06). */
@JvmInline
value class LostAndFoundItemId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): LostAndFoundItemId = LostAndFoundItemId(UuidV7.generate())
    }
}

/**
 * Status de um item de achados e perdidos (D2-E06): `STORED`, guardado; `RETURNED`, devolvido; `DISCARDED`,
 * descartado; `DONATED`, doado. O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class LostAndFoundStatus {
    STORED,
    RETURNED,
    DISCARDED,
    DONATED,
}

/**
 * Origem de uma ocorrência da governança, achado ou dano (D2-E06): `MANUAL`, registrada online; `OFFLINE`, por comando
 * do aparelho; `CHECKLIST`, por uma ação de ocorrência do checklist concluído. O conjunto pode crescer: o cliente trata
 * um valor desconhecido sem falhar.
 */
enum class OccurrenceSource {
    MANUAL,
    OFFLINE,
    CHECKLIST,
}

/** Id de um registro de dano (M6 E06). */
@JvmInline
value class DamageReportId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): DamageReportId = DamageReportId(UuidV7.generate())
    }
}

/**
 * Status de um dano (D4-E06): `REPORTED`, registrado; `REVIEWED`, revisado (valor final, código e vínculo);
 * `CHARGED`, lançado na conta da reserva; `WAIVED`, dispensado com motivo. O conjunto pode crescer: o cliente trata um
 * valor desconhecido sem falhar.
 */
enum class DamageReportStatus {
    REPORTED,
    REVIEWED,
    CHARGED,
    WAIVED,
}

/** Id de um plano do dia da distribuição das tarefas. */
@JvmInline
value class AssignmentPlanId(
    override val value: UUID,
) : TypedId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): AssignmentPlanId = AssignmentPlanId(UuidV7.generate())
    }
}

/**
 * Status do plano do dia (D6-E03): `DRAFT`, o rascunho da sugestão e do ajuste; `PUBLISHED`, gravado nas tarefas pela
 * publicação (Task 3). O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class AssignmentPlanStatus {
    DRAFT,
    PUBLISHED,
}

/**
 * Por que uma camareira com tarefa incluída ou removida não foi avisada na publicação do plano (D1-E03): `NO_USER`, o
 * colaborador não tem usuário vinculado no cadastro (o usuário vem do cadastro, não da escala); `NOT_DELIVERED`, o
 * aviso não chegou ao painel (preferência desligada, usuário inativo ou que não recebe). O conjunto pode crescer: o
 * cliente trata um valor desconhecido sem falhar.
 */
enum class NotNotifiedReason {
    NO_USER,
    NOT_DELIVERED,
}

/**
 * Status de uma tarefa de governança (SPEC §8.2.10). A tarefa nasce `PENDING` (E02); as transições são da E03 e da E04.
 * O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class TaskStatus {
    PENDING,
    ASSIGNED,
    IN_PROGRESS,
    PAUSED,
    AWAITING_INSPECTION,
    REJECTED,
    DONE,
    REFUSED,
    CANCELLED,
}

/**
 * Por que a camareira recusou a tarefa (SPEC §8.4, M6 E04): `DO_NOT_DISTURB`, a UH com o sinal de não perturbe;
 * `GUEST_REFUSED`, o hóspede recusou o serviço. O conjunto pode crescer: o cliente trata um valor desconhecido sem
 * falhar.
 */
enum class RefusalReason {
    DO_NOT_DISTURB,
    GUEST_REFUSED,
}

/**
 * Origem de uma tarefa de governança (RF-GOV-02): saída, troca de UH (na UH de origem), permanência, pré-chegada,
 * faxina programada ou solicitação avulsa da recepção. O conjunto pode crescer: o cliente trata um valor desconhecido
 * sem falhar.
 */
enum class TaskSource {
    CHECKOUT,
    ROOM_MOVE,
    STAYOVER,
    PRE_ARRIVAL,
    SCHEDULED,
    AD_HOC,
}

/**
 * Chave estável dos tipos de limpeza da carga inicial (D5 da M6 E01), que nunca muda: o gestor troca código, nome e
 * configuração e pode desativar o tipo, mas os outros módulos (e a geração de tarefas, E02) o acham pela chave. Os
 * tipos criados pela API não têm chave. O conjunto pode crescer: o cliente trata um valor desconhecido sem falhar.
 */
enum class CleaningTypeKey {
    /** Saída (limpeza do check-out). */
    CHECKOUT,

    /** Permanência (UH ocupada). */
    STAYOVER,

    /** Retoque. */
    TOUCH_UP,

    /** Faxina pesada. */
    DEEP_CLEAN,

    /** Pré-chegada. */
    PRE_ARRIVAL,

    /** Abertura de cama; vem desativada (H9, Q-14). */
    TURNDOWN,
}

/**
 * Códigos de erro estáveis do `housekeeping` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido.
 * Referência inexistente no corpo é `400 …UNKNOWN_…` com o campo (H16).
 */
object HousekeepingErrorCodes {
    /** 400: texto, código ou valor fora do formato (com o campo em `errors[]`), inclusive nome com NUL. */
    val HOUSEKEEPING_DATA_INVALID = ErrorCode("HOUSEKEEPING_DATA_INVALID")

    /** 404: tipo de limpeza inexistente. */
    val CLEANING_TYPE_NOT_FOUND = ErrorCode("CLEANING_TYPE_NOT_FOUND")

    /** 409: outro tipo de limpeza já usa o código (inclusive desativado). */
    val CLEANING_TYPE_CODE_ALREADY_IN_USE = ErrorCode("CLEANING_TYPE_CODE_ALREADY_IN_USE")

    /** 400: crédito para um tipo de UH que não existe (campo `credits`). */
    val CLEANING_TYPE_UNKNOWN_ROOM_TYPE = ErrorCode("CLEANING_TYPE_UNKNOWN_ROOM_TYPE")

    /** 400: o mesmo tipo de UH mais de uma vez nos créditos (campo `credits`). */
    val CLEANING_TYPE_CREDIT_DUPLICATED = ErrorCode("CLEANING_TYPE_CREDIT_DUPLICATED")

    /** 400: o modelo de checklist do vínculo não existe (campo `checklistTemplateId`, H16). */
    val CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE = ErrorCode("CLEANING_TYPE_UNKNOWN_CHECKLIST_TEMPLATE")

    /** 422: o modelo de checklist do vínculo não é do contexto `HOUSEKEEPING`. */
    val CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE = ErrorCode("CLEANING_TYPE_CHECKLIST_CONTEXT_INCOMPATIBLE")

    /** 422: o modelo de checklist do vínculo está desativado. */
    val CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE = ErrorCode("CLEANING_TYPE_CHECKLIST_TEMPLATE_INACTIVE")

    /** 422: o modelo de checklist do vínculo nunca foi publicado. */
    val CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED = ErrorCode("CLEANING_TYPE_CHECKLIST_TEMPLATE_NOT_PUBLISHED")

    /** 422: o tipo de limpeza está desativado (criar, reativar ou trocar o tipo de uma faxina programada). */
    val CLEANING_TYPE_INACTIVE = ErrorCode("CLEANING_TYPE_INACTIVE")

    /** 422: desativar um tipo de limpeza que tem faxina programada ativa. */
    val CLEANING_TYPE_HAS_ACTIVE_SCHEDULES = ErrorCode("CLEANING_TYPE_HAS_ACTIVE_SCHEDULES")

    /** 404: faxina programada inexistente. */
    val SCHEDULED_CLEANING_NOT_FOUND = ErrorCode("SCHEDULED_CLEANING_NOT_FOUND")

    /** 409: já há faxina programada ativa no mesmo local com o mesmo tipo de limpeza. */
    val SCHEDULED_CLEANING_ALREADY_EXISTS = ErrorCode("SCHEDULED_CLEANING_ALREADY_EXISTS")

    /** 400: a faxina programada precisa de exatamente um local, a UH (`roomId`) ou a área (`areaId`). */
    val SCHEDULED_CLEANING_LOCATION_INVALID = ErrorCode("SCHEDULED_CLEANING_LOCATION_INVALID")

    /** 400: a UH ou a área da faxina programada não existe (campo `roomId` ou `areaId`, H16). */
    val SCHEDULED_CLEANING_UNKNOWN_LOCATION = ErrorCode("SCHEDULED_CLEANING_UNKNOWN_LOCATION")

    /** 422: a UH ou a área da faxina programada está desativada. */
    val SCHEDULED_CLEANING_LOCATION_INACTIVE = ErrorCode("SCHEDULED_CLEANING_LOCATION_INACTIVE")

    /** 422: a última faxina (`lastDoneOn`) é posterior à data operacional. */
    val SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE = ErrorCode("SCHEDULED_CLEANING_LAST_DONE_IN_FUTURE")

    /** 400: o tipo de limpeza da faxina programada não existe (campo `cleaningTypeId`, H16). */
    val SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE = ErrorCode("SCHEDULED_CLEANING_UNKNOWN_CLEANING_TYPE")

    /** 404: tarefa de governança inexistente. */
    val HOUSEKEEPING_TASK_NOT_FOUND = ErrorCode("HOUSEKEEPING_TASK_NOT_FOUND")

    /** 400: a tarefa precisa de exatamente um local, a UH (`roomId`) ou a área (`areaId`). */
    val HOUSEKEEPING_TASK_LOCATION_INVALID = ErrorCode("HOUSEKEEPING_TASK_LOCATION_INVALID")

    /** 400: a UH ou a área da tarefa não existe (campo `roomId` ou `areaId`, H16). */
    val HOUSEKEEPING_TASK_UNKNOWN_LOCATION = ErrorCode("HOUSEKEEPING_TASK_UNKNOWN_LOCATION")

    /** 400: o tipo de limpeza da tarefa não existe (campo `cleaningTypeId`, H16). */
    val HOUSEKEEPING_TASK_UNKNOWN_CLEANING_TYPE = ErrorCode("HOUSEKEEPING_TASK_UNKNOWN_CLEANING_TYPE")

    /** 422: a UH ou a área da tarefa está desativada. */
    val HOUSEKEEPING_TASK_LOCATION_INACTIVE = ErrorCode("HOUSEKEEPING_TASK_LOCATION_INACTIVE")

    /** 422: a data da tarefa é anterior à data operacional ou mais de 30 dias depois dela. */
    val HOUSEKEEPING_TASK_DATE_INVALID = ErrorCode("HOUSEKEEPING_TASK_DATE_INVALID")

    /** 409: já há tarefa no mesmo local, na mesma data e com o mesmo tipo de limpeza, em qualquer status (D5-E02). */
    val HOUSEKEEPING_TASK_ALREADY_EXISTS = ErrorCode("HOUSEKEEPING_TASK_ALREADY_EXISTS")

    /**
     * 409: a ação da execução não vale no status atual da tarefa (SPEC §8.4), com `taskStatus` e `action` no problema
     * (padrão `RESERVATION_TRANSITION_NOT_ALLOWED`).
     */
    val HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED = ErrorCode("HOUSEKEEPING_TASK_TRANSITION_NOT_ALLOWED")

    /** 422: iniciar uma tarefa de data posterior à data operacional (D11-E04). */
    val HOUSEKEEPING_TASK_NOT_DUE = ErrorCode("HOUSEKEEPING_TASK_NOT_DUE")

    /**
     * 422 (recusa do comando offline `housekeeping.start-task`, D1-E19): a versão do checklist que o aparelho informou
     * não existe ou não é de um modelo do contexto `HOUSEKEEPING`; nenhuma execução é aberta.
     */
    val HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID = ErrorCode("HOUSEKEEPING_TASK_CHECKLIST_VERSION_INVALID")

    /**
     * Alerta do comando offline `housekeeping.start-task` (D1-E19, RN-22): o aparelho abriu o checklist de um modelo
     * diferente do que a tarefa usa agora (ou a tarefa não usa modelo nenhum); a execução abriu com a versão do
     * aparelho e a trilha registra o modelo esperado e o recebido.
     */
    val HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH = ErrorCode("HOUSEKEEPING_TASK_CHECKLIST_TEMPLATE_MISMATCH")

    /**
     * Alerta dos comandos offline da execução (D4-E19, RN-22): o comando veio de uma ex-dona (camareira com `OWN` que
     * já teve a tarefa) numa tarefa reatribuída, retirada ou cancelada; ficou registrado como conflito, a tarefa ficou
     * marcada (`hasConflict`) e nada mais mudou nela.
     */
    val HOUSEKEEPING_TASK_CONFLICT = ErrorCode("HOUSEKEEPING_TASK_CONFLICT")

    /** 409: a tarefa já tem uma inspeção aberta (D3-E05); conclua-a. */
    val HOUSEKEEPING_INSPECTION_ALREADY_OPEN = ErrorCode("HOUSEKEEPING_INSPECTION_ALREADY_OPEN")

    /**
     * 409: concluir a inspeção de uma tarefa que tem modelo de checklist de inspeção sem tê-la iniciado (D3-E05): o
     * checklist abre no início.
     */
    val HOUSEKEEPING_INSPECTION_NOT_STARTED = ErrorCode("HOUSEKEEPING_INSPECTION_NOT_STARTED")

    /**
     * 409: a inspeção voluntária de uma UH Limpa com inspeção pendente (D4-E05): a pendência é de uma tarefa;
     * inspecione pela tarefa.
     */
    val HOUSEKEEPING_INSPECTION_USE_TASK = ErrorCode("HOUSEKEEPING_INSPECTION_USE_TASK")

    /** 400: a UH da inspeção voluntária não existe (campo `roomId`, H16; D4-E05). */
    val HOUSEKEEPING_INSPECTION_UNKNOWN_ROOM = ErrorCode("HOUSEKEEPING_INSPECTION_UNKNOWN_ROOM")

    /** 409: a inspeção voluntária de uma UH que não está Limpa (Suja ou já Inspecionada, D4-E05). */
    val HOUSEKEEPING_ROOM_NOT_CLEAN = ErrorCode("HOUSEKEEPING_ROOM_NOT_CLEAN")

    /** 400: a ocorrência (achado ou dano) precisa de exatamente um local, a UH (`roomId`) ou a área (`areaId`). */
    val HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID = ErrorCode("HOUSEKEEPING_OCCURRENCE_LOCATION_INVALID")

    /** 400: a UH ou a área da ocorrência não existe (campo `roomId` ou `areaId`, H16). */
    val HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION = ErrorCode("HOUSEKEEPING_OCCURRENCE_UNKNOWN_LOCATION")

    /** 404: item de achados e perdidos inexistente (ou, com o escopo `OWN`, de outro usuário). */
    val HOUSEKEEPING_LOST_FOUND_NOT_FOUND = ErrorCode("HOUSEKEEPING_LOST_FOUND_NOT_FOUND")

    /**
     * 409: o destino não vale no status atual do item (D2-E06: só o item guardado é devolvido, descartado ou doado),
     * com `itemStatus` e `action` no problema.
     */
    val HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED = ErrorCode("HOUSEKEEPING_LOST_FOUND_TRANSITION_NOT_ALLOWED")

    /**
     * 409: descartar ou doar um item antes do fim do prazo de guarda (D2-E06, RF-GOV-08): a data local em que foi
     * achado, no fuso do hotel, mais o prazo de guarda ainda é posterior à data operacional.
     */
    val HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED = ErrorCode("HOUSEKEEPING_LOST_FOUND_NOT_EXPIRED")

    /** 404: dano inexistente (ou, com o escopo `OWN`, de outro usuário). */
    val HOUSEKEEPING_DAMAGE_NOT_FOUND = ErrorCode("HOUSEKEEPING_DAMAGE_NOT_FOUND")

    /**
     * 409: a ação não vale no status atual do dano (D4-E06: revisar e dispensar só `REPORTED` ou `REVIEWED`; cobrar só
     * `REVIEWED`), com `damageStatus` e `action` no problema.
     */
    val HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED = ErrorCode("HOUSEKEEPING_DAMAGE_TRANSITION_NOT_ALLOWED")

    /** 409: cobrar um dano ainda não revisado (`REPORTED`, D4-E06). */
    val HOUSEKEEPING_DAMAGE_NOT_REVIEWED = ErrorCode("HOUSEKEEPING_DAMAGE_NOT_REVIEWED")

    /** 409: cobrar um dano sem reserva vinculada (D4-E06, Review Focus 2): só registro e dispensa. */
    val HOUSEKEEPING_DAMAGE_NO_RESERVATION = ErrorCode("HOUSEKEEPING_DAMAGE_NO_RESERVATION")

    /** 409: a conta da cobrança existe, mas está encerrada ou não é da reserva vinculada ao dano (D4-E06). */
    val HOUSEKEEPING_DAMAGE_FOLIO_INVALID = ErrorCode("HOUSEKEEPING_DAMAGE_FOLIO_INVALID")

    /** 409: o código de lançamento da revisão existe, mas está desativado ou não é `CHARGE` nem `PENALTY` (D4-E06). */
    val HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID = ErrorCode("HOUSEKEEPING_DAMAGE_TRANSACTION_CODE_INVALID")

    /** 409: a reserva da revisão (troca do vínculo) existe, mas não está hospedada (`IN_HOUSE`, D4-E06). */
    val HOUSEKEEPING_DAMAGE_RESERVATION_INVALID = ErrorCode("HOUSEKEEPING_DAMAGE_RESERVATION_INVALID")

    /** 400: o código de lançamento da revisão não existe (campo `transactionCodeId`, H16). */
    val HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE = ErrorCode("HOUSEKEEPING_DAMAGE_UNKNOWN_TRANSACTION_CODE")

    /** 400: a conta da cobrança não existe (campo `folioId`, H16). */
    val HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO = ErrorCode("HOUSEKEEPING_DAMAGE_UNKNOWN_FOLIO")

    /** 400: a reserva da revisão não existe (campo `reservationId`, H16). */
    val HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION = ErrorCode("HOUSEKEEPING_DAMAGE_UNKNOWN_RESERVATION")

    /** 404: ainda não há plano do dia na data. */
    val ASSIGNMENT_PLAN_NOT_FOUND = ErrorCode("ASSIGNMENT_PLAN_NOT_FOUND")

    /** 422: a data do plano é anterior à data operacional ou mais de 7 dias depois dela (D2-E03). */
    val ASSIGNMENT_PLAN_DATE_INVALID = ErrorCode("ASSIGNMENT_PLAN_DATE_INVALID")

    /**
     * 422: o colaborador não está na escala do setor de governança na data (ou fora dos `employeeIds` da sugestão).
     * Um colaborador inexistente dá o mesmo código (D11-E03, exceção à H16: a validação é pela escala da data e não
     * distingue colaborador inexistente de não escalado).
     */
    val ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED = ErrorCode("ASSIGNMENT_EMPLOYEE_NOT_SCHEDULED")

    /**
     * 422: a tarefa não pode entrar no plano: já andou (fora de `PENDING` e `ASSIGNED`) ou está fora do conteúdo do
     * plano (D2-E03: a data dela, ou uma anterior só no plano da data operacional).
     */
    val ASSIGNMENT_TASK_NOT_ASSIGNABLE = ErrorCode("ASSIGNMENT_TASK_NOT_ASSIGNABLE")

    /** 400: a tarefa do ajuste não existe (campo `items` ou `remove`, H16). */
    val ASSIGNMENT_UNKNOWN_TASK = ErrorCode("ASSIGNMENT_UNKNOWN_TASK")
}
