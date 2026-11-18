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
}
