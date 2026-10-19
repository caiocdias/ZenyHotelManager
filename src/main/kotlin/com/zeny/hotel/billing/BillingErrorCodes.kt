package com.zeny.hotel.billing

import com.zeny.hotel.shared.ErrorCode

/** Códigos de erro estáveis do `billing` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido. */
object BillingErrorCodes {
    /** 400: texto, código curto ou valor fora do formato (com o campo em `errors[]`). */
    val BILLING_DATA_INVALID = ErrorCode("BILLING_DATA_INVALID")

    /** 404: código de lançamento inexistente. */
    val TRANSACTION_CODE_NOT_FOUND = ErrorCode("TRANSACTION_CODE_NOT_FOUND")

    /** 400: o lançamento cita um código inexistente (campo `transactionCodeId`). */
    val UNKNOWN_TRANSACTION_CODE = ErrorCode("UNKNOWN_TRANSACTION_CODE")

    /** 422: código de lançamento desativado. */
    val TRANSACTION_CODE_INACTIVE = ErrorCode("TRANSACTION_CODE_INACTIVE")

    /** 409: outro código já usa o código curto (inclusive desativado). */
    val TRANSACTION_CODE_ALREADY_IN_USE = ErrorCode("TRANSACTION_CODE_ALREADY_IN_USE")

    /** 400: taxa de serviço incoerente com o tipo do código, ou percentual fora de 0 a 100. */
    val TRANSACTION_CODE_INVALID = ErrorCode("TRANSACTION_CODE_INVALID")

    /** 404: conta inexistente. */
    val FOLIO_NOT_FOUND = ErrorCode("FOLIO_NOT_FOUND")

    /** 409: conta encerrada não recebe lançamento nem correção. */
    val FOLIO_CLOSED = ErrorCode("FOLIO_CLOSED")

    /** 400: dono da conta sem id (só `WALK_IN` aceita sem). */
    val FOLIO_OWNER_INVALID = ErrorCode("FOLIO_OWNER_INVALID")

    /** 400: empresa (dona ou pagadora) inexistente. */
    val UNKNOWN_COMPANY = ErrorCode("UNKNOWN_COMPANY")

    /** 422: empresa (dona ou pagadora) desativada. */
    val COMPANY_INACTIVE = ErrorCode("COMPANY_INACTIVE")

    /** 404: lançamento inexistente nesta conta. */
    val POSTING_NOT_FOUND = ErrorCode("POSTING_NOT_FOUND")

    /** 400: quantidade, valor unitário ou descrição fora da faixa, ou valor que arredonda a zero. */
    val POSTING_INVALID = ErrorCode("POSTING_INVALID")

    /** 422: só um lançamento normal é estornado ou ajustado (nunca um estorno nem um ajuste). */
    val POSTING_NOT_CORRECTABLE = ErrorCode("POSTING_NOT_CORRECTABLE")

    /** 409: o lançamento já foi estornado (não aceita outro estorno nem ajuste). */
    val POSTING_ALREADY_REVERSED = ErrorCode("POSTING_ALREADY_REVERSED")

    /** 422: os ajustes já zeraram o lançamento; não resta nada a estornar. */
    val POSTING_NOTHING_TO_REVERSE = ErrorCode("POSTING_NOTHING_TO_REVERSE")

    /** 422: o ajuste passa do que ainda resta do lançamento. */
    val ADJUSTMENT_EXCEEDS_REMAINING = ErrorCode("ADJUSTMENT_EXCEEDS_REMAINING")

    /** 400: a requisição cita uma conta inexistente (destino, origem de regra ou parte da divisão; com o campo). */
    val UNKNOWN_FOLIO = ErrorCode("UNKNOWN_FOLIO")

    /** 404: regra de roteamento inexistente. */
    val ROUTING_RULE_NOT_FOUND = ErrorCode("ROUTING_RULE_NOT_FOUND")

    /**
     * 400: regra sem critério ou com os dois (códigos e grupo de receita), com código repetido ou mais de 50, ou
     * período com o fim antes do início (com o campo).
     */
    val ROUTING_RULE_INVALID = ErrorCode("ROUTING_RULE_INVALID")

    /**
     * 409: outra regra ativa da mesma conta de origem e do mesmo nível (as duas por código, com algum código em
     * comum, ou as duas pelo mesmo grupo de receita) tem período que se cruza com o desta.
     */
    val ROUTING_RULE_OVERLAP = ErrorCode("ROUTING_RULE_OVERLAP")

    /** 422: a regra formaria um ciclo de roteamento (a cadeia voltaria à origem), inclusive origem = destino. */
    val ROUTING_RULE_CYCLE = ErrorCode("ROUTING_RULE_CYCLE")

    /** 409: a conta de destino de uma regra (na criação ou no lançamento roteado) está encerrada. */
    val ROUTING_TARGET_CLOSED = ErrorCode("ROUTING_TARGET_CLOSED")

    /** 400: transferência sem lançamentos, com lançamento repetido ou para a própria conta (com o campo). */
    val TRANSFER_INVALID = ErrorCode("TRANSFER_INVALID")

    /** 422: só um lançamento normal ou a entrada de uma transferência é transferido ou dividido. */
    val POSTING_NOT_TRANSFERABLE = ErrorCode("POSTING_NOT_TRANSFERABLE")

    /** 422: os ajustes ou transferências já zeraram o lançamento; não resta nada a transferir. */
    val POSTING_NOTHING_TO_TRANSFER = ErrorCode("POSTING_NOTHING_TO_TRANSFER")

    /**
     * 400: divisão com menos de 2 ou mais de 10 partes, conta repetida, parte sem o valor do modo (`percent` ou
     * `amount`) ou com valor não positivo, ou percentuais que não somam exatamente 100 (com o campo).
     */
    val SPLIT_INVALID = ErrorCode("SPLIT_INVALID")

    /** 422: na divisão por valor, as partes não somam exatamente o que ainda resta do lançamento. */
    val SPLIT_TOTAL_MISMATCH = ErrorCode("SPLIT_TOTAL_MISMATCH")

    /**
     * 422: a taxa de serviço acompanha o item (M3 E06): não é estornada, ajustada, transferida nem dividida à parte;
     * corrija ou mova o item, ou remova a taxa a pedido do cliente.
     */
    val SERVICE_CHARGE_FOLLOWS_ITEM = ErrorCode("SERVICE_CHARGE_FOLLOWS_ITEM")

    /** 422: a remoção a pedido cita um lançamento que não é taxa de serviço vinculada a um item. */
    val POSTING_NOT_SERVICE_CHARGE = ErrorCode("POSTING_NOT_SERVICE_CHARGE")
}
