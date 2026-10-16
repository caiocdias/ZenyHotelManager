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

    /** 404: execução de checklist inexistente (ou fora do escopo `OWN` de quem consulta). */
    val CHECKLIST_EXECUTION_NOT_FOUND = ErrorCode("CHECKLIST_EXECUTION_NOT_FOUND")

    /** 404: rotina de checklist inexistente. */
    val CHECKLIST_ROUTINE_NOT_FOUND = ErrorCode("CHECKLIST_ROUTINE_NOT_FOUND")

    /**
     * 400: pedido de início fora do formato: nem modelo nem contexto (ou os dois), ou o tipo e o id do sujeito sem
     * o outro ou fora do formato.
     */
    val CHECKLIST_EXECUTION_INVALID = ErrorCode("CHECKLIST_EXECUTION_INVALID")

    /** 422: nenhum modelo ativo e publicado do contexto vale para o sujeito (RF-CHK-04). */
    val CHECKLIST_NO_APPLICABLE_TEMPLATE = ErrorCode("CHECKLIST_NO_APPLICABLE_TEMPLATE")

    /** 422: o modelo nunca foi publicado: não há versão para executar. */
    val CHECKLIST_TEMPLATE_NOT_PUBLISHED = ErrorCode("CHECKLIST_TEMPLATE_NOT_PUBLISHED")

    /** 422: a versão informada (offline) não é do modelo da execução. */
    val CHECKLIST_VERSION_MISMATCH = ErrorCode("CHECKLIST_VERSION_MISMATCH")

    /** 400: a resposta cita uma chave que não existe na versão da execução. */
    val CHECKLIST_ANSWER_UNKNOWN_ITEM = ErrorCode("CHECKLIST_ANSWER_UNKNOWN_ITEM")

    /** 400: duas respostas para o mesmo item. */
    val CHECKLIST_ANSWER_DUPLICATED = ErrorCode("CHECKLIST_ANSWER_DUPLICATED")

    /**
     * 400: a resposta não cabe no tipo do item (o campo do tipo ausente ou outro campo preenchido; marcação fora de
     * `OK`/`NOT_OK`/`NOT_APPLICABLE`; opção que não existe; quantidade negativa, grande demais ou com casas
     * demais; texto ou observação grandes demais).
     */
    val CHECKLIST_ANSWER_INVALID = ErrorCode("CHECKLIST_ANSWER_INVALID")

    /**
     * 422: concluir sem resposta a um item obrigatório visível (RF-CHK-03); o problema lista as chaves em
     * `missingItems`. Um item oculto pela condição não é exigido.
     */
    val CHECKLIST_REQUIRED_ITEMS_MISSING = ErrorCode("CHECKLIST_REQUIRED_ITEMS_MISSING")

    /** 409: responder ou concluir uma execução (de rotina) que ninguém iniciou. */
    val CHECKLIST_EXECUTION_NOT_STARTED = ErrorCode("CHECKLIST_EXECUTION_NOT_STARTED")

    /** 409: iniciar uma execução de rotina que já foi iniciada (por outra pessoa, online). */
    val CHECKLIST_EXECUTION_ALREADY_STARTED = ErrorCode("CHECKLIST_EXECUTION_ALREADY_STARTED")

    /** 409: já existe uma execução com o id informado (`ChecklistsApi.startExecution`). */
    val CHECKLIST_EXECUTION_ALREADY_EXISTS = ErrorCode("CHECKLIST_EXECUTION_ALREADY_EXISTS")

    /** 409: responder ou concluir uma execução já concluída. */
    val CHECKLIST_EXECUTION_ALREADY_COMPLETED = ErrorCode("CHECKLIST_EXECUTION_ALREADY_COMPLETED")

    /** 403: só o executor (quem iniciou) responde e conclui a execução. */
    val CHECKLIST_EXECUTOR_REQUIRED = ErrorCode("CHECKLIST_EXECUTOR_REQUIRED")

    /** 422: a foto citada não existe (ou foi apagada); envie-a antes (`POST /api/v1/files`). */
    val CHECKLIST_PHOTO_NOT_FOUND = ErrorCode("CHECKLIST_PHOTO_NOT_FOUND")

    /**
     * 422: a foto citada não serve: é de outra finalidade (não `checklists.photo`), foi enviada por outro usuário
     * ou já está vinculada a outro registro. Online, recusa a requisição; no comando offline (e pela API pública),
     * é um **alerta**: a resposta fica sem a foto e o comando é aplicado (RN-22).
     */
    val CHECKLIST_PHOTO_INVALID = ErrorCode("CHECKLIST_PHOTO_INVALID")

    /** 400: o modelo da rotina não existe. */
    val UNKNOWN_CHECKLIST_TEMPLATE = ErrorCode("UNKNOWN_CHECKLIST_TEMPLATE")

    /** 400: o setor da rotina não existe. */
    val UNKNOWN_DEPARTMENT = ErrorCode("UNKNOWN_DEPARTMENT")

    /**
     * 400: rotina fora do formato: nome, frequência sem os dias da semana (ou dias numa diária), horário com
     * segundos, local sem o tipo ou o id.
     */
    val CHECKLIST_ROUTINE_INVALID = ErrorCode("CHECKLIST_ROUTINE_INVALID")

    // --- Alertas dos comandos offline (RN-22: aplicado e sinalizado, não descartado) -------------------------

    /** Alerta: a foto citada ainda não chegou ao servidor (ou foi expurgada): a resposta ficou sem a foto (H10). */
    val CHECKLIST_PHOTO_MISSING = ErrorCode("CHECKLIST_PHOTO_MISSING")

    /**
     * Alerta: a rotina já tinha sido assumida por outra pessoa; a execução offline foi registrada à parte, ligada a
     * ela (`duplicateOf`), para a governança conferir.
     */
    val CHECKLIST_ROUTINE_ALREADY_TAKEN = ErrorCode("CHECKLIST_ROUTINE_ALREADY_TAKEN")

    /** Alerta: a execução offline usou uma versão do modelo que já foi substituída; a versão usada ficou guardada. */
    val CHECKLIST_VERSION_OUTDATED = ErrorCode("CHECKLIST_VERSION_OUTDATED")
}
