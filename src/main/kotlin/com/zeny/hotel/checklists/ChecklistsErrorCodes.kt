package com.zeny.hotel.checklists

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do módulo `checklists` (ARCHITECTURE §7). Um código publicado não muda. */
object ChecklistsErrorCodes {
    /** 404: modelo de checklist inexistente. */
    val CHECKLIST_TEMPLATE_NOT_FOUND = ErrorCode("CHECKLIST_TEMPLATE_NOT_FOUND")

    /** 404: versão inexistente (ou de outro modelo). */
    val CHECKLIST_VERSION_NOT_FOUND = ErrorCode("CHECKLIST_VERSION_NOT_FOUND")

    /**
     * 400: nome, título, rótulo, chave, unidade ou categoria em branco, grandes demais ou fora do formato;
     * modelo sem seção, seção sem item ou com itens demais; unidade num item que não é de quantidade ou número.
     */
    val CHECKLIST_DATA_INVALID = ErrorCode("CHECKLIST_DATA_INVALID")

    /** 400: duas perguntas do modelo com a mesma chave (a chave identifica o item entre as versões). */
    val CHECKLIST_ITEM_KEY_DUPLICATED = ErrorCode("CHECKLIST_ITEM_KEY_DUPLICATED")

    /**
     * 400: item de escolha sem ao menos duas opções, com opções repetidas ou grandes demais, ou opções num item
     * que não é de escolha.
     */
    val CHECKLIST_ITEM_OPTIONS_INVALID = ErrorCode("CHECKLIST_ITEM_OPTIONS_INVALID")

    /** 400: a condição (`visibleWhen`) cita uma chave que não existe no modelo. */
    val CHECKLIST_CONDITION_UNKNOWN_ITEM = ErrorCode("CHECKLIST_CONDITION_UNKNOWN_ITEM")

    /** 400: a condição cita o próprio item ou um item posterior (só vale item anterior, na ordem do modelo). */
    val CHECKLIST_CONDITION_FORWARD_REFERENCE = ErrorCode("CHECKLIST_CONDITION_FORWARD_REFERENCE")

    /**
     * 400: a resposta da condição não cabe no item citado (marcação: `OK`, `NOT_OK` ou `NOT_APPLICABLE`;
     * escolha: uma das opções), ou o item citado não é de marcação nem de escolha.
     */
    val CHECKLIST_CONDITION_INCOMPATIBLE = ErrorCode("CHECKLIST_CONDITION_INCOMPATIBLE")

    /** 400: ação sem os parâmetros do tipo dela, com parâmetros de outro tipo, fora da faixa ou repetida no item. */
    val CHECKLIST_ACTION_INVALID = ErrorCode("CHECKLIST_ACTION_INVALID")

    /**
     * 400: ação num tipo de item que não a dispara: baixa de estoque e troca de enxoval só em item de quantidade;
     * abrir ocorrência só em item de marcação ("não ok").
     */
    val CHECKLIST_ACTION_ITEM_TYPE_MISMATCH = ErrorCode("CHECKLIST_ACTION_ITEM_TYPE_MISMATCH")

    /** 400: regra de aplicabilidade sem nenhum critério, com IDs demais, ou regras demais. */
    val CHECKLIST_APPLICABILITY_INVALID = ErrorCode("CHECKLIST_APPLICABILITY_INVALID")

    /** 422: o rascunho é igual à última versão publicada: não há o que publicar. */
    val CHECKLIST_NOTHING_TO_PUBLISH = ErrorCode("CHECKLIST_NOTHING_TO_PUBLISH")

    /** 422: o modelo está desativado e não é editado nem publicado (reative antes). */
    val CHECKLIST_TEMPLATE_INACTIVE = ErrorCode("CHECKLIST_TEMPLATE_INACTIVE")
}
