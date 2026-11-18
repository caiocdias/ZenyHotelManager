package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.StorableRange
import org.springframework.core.convert.converter.Converter
import org.springframework.format.FormatterRegistry
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * Datas e instantes estritos em query string e caminho (ARCHITECTURE §7, "Datas e valores"), a mesma regra do corpo
 * JSON (`StrictDateTimeJson`): só o texto `ISO_LOCAL_DATE` ou `ISO_INSTANT`, sem espaço nas pontas, dentro do
 * [StorableRange]. Vale para todo `@RequestParam` e `@PathVariable` de [LocalDate] ou [Instant], sem código por
 * endpoint; o formato curto do locale, a época em milissegundos, a data-hora numa data e o valor fora da faixa são
 * recusados e a API devolve `400 VALIDATION_FAILED` com `INVALID_FORMAT` no parâmetro (`ApiExceptionHandler`), sem
 * repetir o valor recebido.
 *
 * O texto vazio é "ausente" (decisão D2 da E20): num parâmetro opcional o Spring já o trata assim, e o conversor
 * devolve `null` para manter o comportamento; num obrigatório sai `400 REQUIRED`.
 */
internal fun FormatterRegistry.addStrictParameterFormats() {
    addConverter(
        String::class.java,
        LocalDate::class.java,
        nullable<LocalDate> { text ->
            strictRead(text, DateTimeFormatter.ISO_LOCAL_DATE, LocalDate::from, StorableRange::contains)
        },
    )
    addConverter(
        String::class.java,
        Instant::class.java,
        nullable<Instant> { text ->
            strictRead(text, DateTimeFormatter.ISO_INSTANT, Instant::from, StorableRange::contains)
        },
    )
}

/**
 * O `Converter` do Spring devolve `null` para "ausente", mas o Kotlin vê o retorno como não anulável: o conversor
 * é declarado anulável e entra no registro com a assinatura do Spring.
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> nullable(convert: (String) -> T?): Converter<String, T> =
    Converter<String, T?> { convert(it) } as Converter<String, T>

/** `null` para o texto vazio (D2); recusa, sem repetir o valor, o que não é ISO ou cai fora da faixa. */
private fun <T : Any> strictRead(
    text: String,
    format: DateTimeFormatter,
    from: (TemporalAccessor) -> T,
    inRange: (T) -> Boolean,
): T? {
    if (text.isEmpty()) return null
    val value =
        try {
            from(format.parse(text))
        } catch (_: DateTimeException) {
            null
        }
    return value?.takeIf(inRange) ?: throw IllegalArgumentException("esperado o texto ISO-8601 dentro da faixa")
}
