package com.zeny.hotel.rates

import com.zeny.hotel.billing.BillingErrorCodes
import com.zeny.hotel.rooms.RoomsErrorCodes
import com.zeny.hotel.shared.ErrorCode

/**
 * Códigos de erro estáveis do `rates` (ARCHITECTURE §7): um código publicado não muda de nome nem de sentido. As
 * recusas sobre um tipo de UH ou um código de lançamento citado no corpo usam os mesmos códigos dos módulos donos.
 */
object RatesErrorCodes {
    /** 400: texto, código curto ou valor fora do formato (com o campo em `errors[]`). */
    val RATES_DATA_INVALID = ErrorCode("RATES_DATA_INVALID")

    /** 404: plano tarifário inexistente. */
    val RATE_PLAN_NOT_FOUND = ErrorCode("RATE_PLAN_NOT_FOUND")

    /** 404: política de cancelamento inexistente. */
    val CANCELLATION_POLICY_NOT_FOUND = ErrorCode("CANCELLATION_POLICY_NOT_FOUND")

    /** 404: política de garantia inexistente. */
    val GUARANTEE_POLICY_NOT_FOUND = ErrorCode("GUARANTEE_POLICY_NOT_FOUND")

    /** 404: pacote inexistente. */
    val PACKAGE_NOT_FOUND = ErrorCode("PACKAGE_NOT_FOUND")

    /** 409: outro plano já usa o código (inclusive desativado). */
    val RATE_PLAN_CODE_ALREADY_IN_USE = ErrorCode("RATE_PLAN_CODE_ALREADY_IN_USE")

    /** 409: outra política de cancelamento já usa o nome (sem diferenciar maiúsculas, inclusive desativada). */
    val CANCELLATION_POLICY_NAME_ALREADY_IN_USE = ErrorCode("CANCELLATION_POLICY_NAME_ALREADY_IN_USE")

    /** 409: outra política de garantia já usa o nome (sem diferenciar maiúsculas, inclusive desativada). */
    val GUARANTEE_POLICY_NAME_ALREADY_IN_USE = ErrorCode("GUARANTEE_POLICY_NAME_ALREADY_IN_USE")

    /** 409: outro pacote já usa o código (inclusive desativado). */
    val PACKAGE_CODE_ALREADY_IN_USE = ErrorCode("PACKAGE_CODE_ALREADY_IN_USE")

    /** 400: multa incoerente com o tipo (o valor do tipo faltando, outro valor informado ou fora da faixa). */
    val CANCELLATION_POLICY_INVALID = ErrorCode("CANCELLATION_POLICY_INVALID")

    /** 400: garantia incoerente com o tipo (depósito sem percentual ou prazo, ou com eles em outro tipo). */
    val GUARANTEE_POLICY_INVALID = ErrorCode("GUARANTEE_POLICY_INVALID")

    /** 400: pacote incoerente (preço por estadia lançado todo dia, preço fora da faixa). */
    val PACKAGE_INVALID = ErrorCode("PACKAGE_INVALID")

    /** 400: o tipo de UH citado não existe (o código do `rooms`; campo `roomTypeIds`). */
    val UNKNOWN_ROOM_TYPE: ErrorCode = RoomsErrorCodes.UNKNOWN_ROOM_TYPE

    /** 422: o tipo de UH citado está desativado (o código do `rooms`). */
    val ROOM_TYPE_INACTIVE: ErrorCode = RoomsErrorCodes.ROOM_TYPE_INACTIVE

    /** 400: o código de lançamento citado não existe (o código do `billing`; campo `transactionCodeId`). */
    val UNKNOWN_TRANSACTION_CODE: ErrorCode = BillingErrorCodes.UNKNOWN_TRANSACTION_CODE

    /** 422: o código de lançamento citado está desativado (o código do `billing`). */
    val TRANSACTION_CODE_INACTIVE: ErrorCode = BillingErrorCodes.TRANSACTION_CODE_INACTIVE

    /** 422: o pacote só lança num código de consumo (`CHARGE`). */
    val TRANSACTION_CODE_KIND_NOT_ALLOWED = ErrorCode("TRANSACTION_CODE_KIND_NOT_ALLOWED")

    /** 400: a política de cancelamento citada não existe (campo `cancellationPolicyId`). */
    val UNKNOWN_CANCELLATION_POLICY = ErrorCode("UNKNOWN_CANCELLATION_POLICY")

    /** 400: a política de garantia citada não existe (campo `guaranteePolicyId`). */
    val UNKNOWN_GUARANTEE_POLICY = ErrorCode("UNKNOWN_GUARANTEE_POLICY")

    /** 400: algum pacote citado não existe (campo `packageIds`). */
    val UNKNOWN_PACKAGE = ErrorCode("UNKNOWN_PACKAGE")

    /** 422: a política de cancelamento citada está desativada. */
    val CANCELLATION_POLICY_INACTIVE = ErrorCode("CANCELLATION_POLICY_INACTIVE")

    /** 422: a política de garantia citada está desativada. */
    val GUARANTEE_POLICY_INACTIVE = ErrorCode("GUARANTEE_POLICY_INACTIVE")

    /** 422: algum pacote citado está desativado. */
    val PACKAGE_INACTIVE = ErrorCode("PACKAGE_INACTIVE")

    /** 422: a política de cancelamento é usada por um plano ativo e não é desativada. */
    val CANCELLATION_POLICY_IN_USE = ErrorCode("CANCELLATION_POLICY_IN_USE")

    /** 422: a política de garantia é usada por um plano ativo e não é desativada. */
    val GUARANTEE_POLICY_IN_USE = ErrorCode("GUARANTEE_POLICY_IN_USE")

    /** 422: o pacote está num plano ativo e não é desativado. */
    val PACKAGE_IN_USE = ErrorCode("PACKAGE_IN_USE")

    // --- Preços, derivação e temporadas (M4 E02) ---------------------------------------------------------------

    /** 400: valor de preço fora da faixa (de 0,00 a 1.000.000,00), com o campo em `errors[]`. */
    val RATE_PRICE_INVALID = ErrorCode("RATE_PRICE_INVALID")

    /**
     * 400: período sem datas (fim antes do início, ou nenhuma data nos dias da semana informados); campo `to` ou
     * `weekdays`.
     */
    val RATE_PRICE_PERIOD_INVALID = ErrorCode("RATE_PRICE_PERIOD_INVALID")

    /** 400: período acima de 366 dias numa só requisição (decisão do usuário na E02); campo `to`. */
    val RATE_PRICE_PERIOD_TOO_LONG = ErrorCode("RATE_PRICE_PERIOD_TOO_LONG")

    /** 400: a seleção informa a temporada e o período juntos, ou nenhum dos dois; campo `seasonId`. */
    val RATE_PRICE_SELECTION_INVALID = ErrorCode("RATE_PRICE_SELECTION_INVALID")

    /** 422: a edição começa antes da data operacional (ou a temporada já terminou). */
    val RATE_PRICE_PERIOD_IN_PAST = ErrorCode("RATE_PRICE_PERIOD_IN_PAST")

    /** 400: preço de criança fora das faixas atuais do hotel, ou faixa repetida; campo `prices.children[i]`. */
    val RATE_PRICE_CHILD_BANDS_INVALID = ErrorCode("RATE_PRICE_CHILD_BANDS_INVALID")

    /** 422: o tipo de UH informado não está no plano. */
    val ROOM_TYPE_NOT_IN_PLAN = ErrorCode("ROOM_TYPE_NOT_IN_PLAN")

    /** 422: o plano é derivado: os preços vêm do plano base e não são gravados nele. */
    val RATE_PLAN_DERIVED = ErrorCode("RATE_PLAN_DERIVED")

    /** 422: o plano tem preços próprios a partir da data operacional; retire-os antes de torná-lo derivado. */
    val RATE_PLAN_HAS_PRICES = ErrorCode("RATE_PLAN_HAS_PRICES")

    /** 422: a derivação levaria o plano a derivar de si mesmo (direta ou indiretamente). */
    val RATE_PLAN_DERIVATION_CYCLE = ErrorCode("RATE_PLAN_DERIVATION_CYCLE")

    /** 422: a derivação passaria de 5 níveis (contando os planos que derivam deste). */
    val RATE_PLAN_DERIVATION_TOO_DEEP = ErrorCode("RATE_PLAN_DERIVATION_TOO_DEEP")

    /** 400: regra de derivação incoerente (percentual e valor juntos, faltando ou fora da faixa), com o campo. */
    val RATE_PLAN_DERIVATION_INVALID = ErrorCode("RATE_PLAN_DERIVATION_INVALID")

    /**
     * 400: o plano citado não existe: o base de uma derivação (campo `basePlanId`) ou, desde a E06, o plano da cotação
     * (`ratePlanIds`) e do calendário (`ratePlanId`, no `reservations`). Também é motivo de recusa de `priceFor` e
     * `isSellable`.
     */
    val UNKNOWN_RATE_PLAN = ErrorCode("UNKNOWN_RATE_PLAN")

    /** 404: temporada inexistente. */
    val SEASON_NOT_FOUND = ErrorCode("SEASON_NOT_FOUND")

    /** 409: outra temporada já usa o nome (sem diferenciar maiúsculas, inclusive desativada). */
    val SEASON_NAME_ALREADY_IN_USE = ErrorCode("SEASON_NAME_ALREADY_IN_USE")

    /** 400: temporada com o fim antes do início ou mais de 366 dias; campo `endsOn`. */
    val SEASON_INVALID = ErrorCode("SEASON_INVALID")

    /** 400: a temporada citada não existe (campo `seasonId`). */
    val UNKNOWN_SEASON = ErrorCode("UNKNOWN_SEASON")

    /** 422: a temporada citada está desativada. */
    val SEASON_INACTIVE = ErrorCode("SEASON_INACTIVE")

    // --- Restrições (M4 E03) -------------------------------------------------------------------------------------

    /**
     * 400: valor de restrição fora da faixa (estadia de 1 a 365 noites, antecedência de 0 a 730 dias; negativo
     * também), o mesmo campo em `set` e em `clear`, ou nada a mudar; com o campo em `errors[]`.
     */
    val RESTRICTION_INVALID = ErrorCode("RESTRICTION_INVALID")

    /**
     * 400: o pedido traz o mínimo maior que o máximo (estadia ou antecedência); campo `set.maxStay` ou
     * `set.maxAdvanceDays`.
     */
    val RESTRICTION_RANGE_INVALID = ErrorCode("RESTRICTION_RANGE_INVALID")

    /**
     * 422: o mínimo ficaria maior que o máximo com o que já está gravado: na mesma restrição (o pedido muda só um dos
     * dois) ou na restrição efetiva de algum tipo de UH (a do tipo sobre a geral do plano).
     */
    val RESTRICTION_RANGE_CONFLICT = ErrorCode("RESTRICTION_RANGE_CONFLICT")

    /** 400: período sem datas (fim antes do início, ou nenhuma data nos dias da semana); campo `to` ou `weekdays`. */
    val RESTRICTION_PERIOD_INVALID = ErrorCode("RESTRICTION_PERIOD_INVALID")

    /** 400: período acima de 366 dias numa só requisição; campo `to`. */
    val RESTRICTION_PERIOD_TOO_LONG = ErrorCode("RESTRICTION_PERIOD_TOO_LONG")

    /** 400: a seleção informa a temporada e o período juntos, ou nenhum dos dois; campo `seasonId`. */
    val RESTRICTION_SELECTION_INVALID = ErrorCode("RESTRICTION_SELECTION_INVALID")

    /** 422: a edição começa antes da data operacional (ou a temporada já terminou). */
    val RESTRICTION_PERIOD_IN_PAST = ErrorCode("RESTRICTION_PERIOD_IN_PAST")

    // --- Preço e vendabilidade de uma estadia (M4 E04) ---------------------------------------------------------------
    // Os motivos de recusa de `RatesApi.priceFor` e `isSellable` (`SaleRefusal.code`), além de `UNKNOWN_RATE_PLAN`,
    // `UNKNOWN_ROOM_TYPE`, `ROOM_TYPE_NOT_IN_PLAN` e `ROOM_TYPE_INACTIVE`, acima; quem chama recusa com `422` (ou
    // `400`, nos dois `UNKNOWN_…`, com o campo). Só `STAY_INVALID` é lançado.

    /**
     * 400 (lançado): estadia fora do formato: saída que não é depois da chegada (campo `departure`), mais de 365
     * noites, nenhum adulto (campo `adults`) ou idade de criança negativa ou acima da idade limite de criança do hotel
     * (campo `childAges[i]`; acima dela, a pessoa conta como adulto).
     */
    val STAY_INVALID = ErrorCode("STAY_INVALID")

    /** O plano está desativado. */
    val RATE_PLAN_INACTIVE = ErrorCode("RATE_PLAN_INACTIVE")

    /** O plano não é vendido no canal da consulta. */
    val SALES_CHANNEL_NOT_ALLOWED = ErrorCode("SALES_CHANNEL_NOT_ALLOWED")

    /** Mais adultos ou mais crianças que a ocupação máxima do tipo de UH (`RoomsApi`). */
    val OCCUPANCY_EXCEEDS_ROOM_TYPE = ErrorCode("OCCUPANCY_EXCEEDS_ROOM_TYPE")

    /** A chegada é antes da data operacional do hotel (RN-01). */
    val ARRIVAL_BEFORE_BUSINESS_DATE = ErrorCode("ARRIVAL_BEFORE_BUSINESS_DATE")

    /** Antecedência (dias da data operacional até a chegada) abaixo da mínima da data de chegada. */
    val MIN_ADVANCE_NOT_MET = ErrorCode("MIN_ADVANCE_NOT_MET")

    /** Antecedência acima da máxima da data de chegada. */
    val MAX_ADVANCE_EXCEEDED = ErrorCode("MAX_ADVANCE_EXCEEDED")

    /** A data de chegada está fechada para chegada (CTA). */
    val CLOSED_TO_ARRIVAL = ErrorCode("CLOSED_TO_ARRIVAL")

    /** A data de saída está fechada para saída (CTD). */
    val CLOSED_TO_DEPARTURE = ErrorCode("CLOSED_TO_DEPARTURE")

    /** Menos noites que a estadia mínima da data de chegada. */
    val MIN_STAY_NOT_MET = ErrorCode("MIN_STAY_NOT_MET")

    /** Mais noites que a estadia máxima da data de chegada. */
    val MAX_STAY_EXCEEDED = ErrorCode("MAX_STAY_EXCEEDED")

    /** Alguma noite da estadia está fechada para venda (as datas em `dates`). */
    val STAY_DATE_CLOSED = ErrorCode("STAY_DATE_CLOSED")

    /** Alguma noite não tem preço gravado para o tipo de UH no plano (ou no plano raiz de um derivado). */
    val RATE_NOT_PRICED = ErrorCode("RATE_NOT_PRICED")

    /**
     * Alguma noite não tem preço para o número de adultos (dois adultos ou adulto extra sem preço, ou a lacuna que a
     * derivação deixou).
     */
    val OCCUPANCY_NOT_PRICED = ErrorCode("OCCUPANCY_NOT_PRICED")

    /** Alguma noite não tem preço na faixa etária atual de uma criança (sem preço, ou só com o preço obsoleto). */
    val CHILD_AGE_NOT_PRICED = ErrorCode("CHILD_AGE_NOT_PRICED")

    /** Os pacotes inclusos passam da diária numa noite (a hospedagem ficaria negativa). */
    val INCLUDED_PACKAGES_EXCEED_RATE = ErrorCode("INCLUDED_PACKAGES_EXCEED_RATE")
}
