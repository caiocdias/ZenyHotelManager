package com.zeny.hotel.housekeeping.application

/**
 * O formato das chaves de item e de opção do `checklists` (`ChecklistContentRules.KEY`, interno a ele), para conferir
 * na entrada do `housekeeping` as chaves que vêm do cliente (itens reprovados da inspeção, resumo do conflito offline)
 * sem depender de detalhe interno do outro módulo. Uma cópia só: se o `checklists` mudar o formato, muda aqui.
 */
internal const val CHECKLIST_ITEM_KEY = "[a-z0-9][a-z0-9_-]{0,39}"
