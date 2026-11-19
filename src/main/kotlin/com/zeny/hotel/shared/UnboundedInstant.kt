package com.zeny.hotel.shared

/**
 * Marca um [java.time.Instant] de corpo JSON que fica fora da checagem do [StorableRange] na leitura (o formato
 * ISO-8601 continua estrito). Só para o que a aplicação julga por regra própria, como o `deviceClock` e o
 * `occurredAt` do envelope offline, recusados por comando com `DEVICE_TIME_INVALID`. Os demais instantes do corpo
 * seguem com a faixa.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class UnboundedInstant
