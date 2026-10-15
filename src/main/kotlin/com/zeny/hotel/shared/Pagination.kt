package com.zeny.hotel.shared

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springdoc.core.annotations.ParameterObject
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Parâmetros de paginação por cursor (`?cursor=…&limit=…`, ARCHITECTURE §7). Num controller:
 *
 * ```kotlin
 * @GetMapping
 * fun list(@Valid page: PageParams): CursorPage<RoomDto> {
 *     val after = page.decodedCursor()?.uuid(0)
 *     val rows = rooms.findAfter(after, page.limit + 1)        // uma linha a mais
 *     return CursorPage.fromRows(rows, page.limit) { PageCursor.of(it.id.toString()) }.map(::toDto)
 * }
 * ```
 *
 * Sem o `@Valid`, o limite não é conferido.
 */
@ParameterObject
data class PageParams(
    @field:Parameter(
        description = "Cursor opaco devolvido em `nextCursor` pela página anterior; omita na primeira página.",
    )
    val cursor: String? = null,
    @field:Parameter(description = "Quantidade máxima de itens na página.")
    @field:Schema(defaultValue = DEFAULT_LIMIT.toString(), minimum = "1", maximum = MAX_LIMIT.toString())
    @field:Min(1)
    @field:Max(MAX_LIMIT.toLong())
    val limit: Int = DEFAULT_LIMIT,
) {
    /** O cursor decodificado, ou `null` na primeira página; cursor inválido é `400 INVALID_CURSOR`. */
    fun decodedCursor(): PageCursor? = cursor?.let(PageCursor::decode)

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

/**
 * Uma página de resultados. [nextCursor] é `null` na última página; senão, o cliente o manda
 * de volta em `cursor` para pedir a próxima. A ordem dos itens é a da consulta, que precisa
 * ser total e estável (termine a ordenação pelo id, que é UUIDv7).
 */
@Schema(description = "Página de resultados paginada por cursor.")
data class CursorPage<T>(
    val items: List<T>,
    @field:Schema(description = "Cursor da próxima página; nulo quando esta é a última.", nullable = true)
    val nextCursor: String?,
) {
    fun <R> map(transform: (T) -> R): CursorPage<R> = CursorPage(items.map(transform), nextCursor)

    companion object {
        /**
         * Monta a página a partir de até `limit + 1` linhas, na ordem da consulta: a linha a
         * mais só indica que há próxima página e não é devolvida. O cursor aponta para o último
         * item devolvido; [cursorOf] diz quais chaves da ordenação identificam esse item.
         */
        fun <T> fromRows(
            rows: List<T>,
            limit: Int,
            cursorOf: (T) -> PageCursor,
        ): CursorPage<T> {
            require(limit > 0) { "O limite da página precisa ser positivo: $limit" }
            val items = rows.take(limit)
            val next = if (rows.size > limit) cursorOf(items.last()).encode() else null
            return CursorPage(items, next)
        }
    }
}

/**
 * Cursor de paginação: as chaves de ordenação do último item da página (keyset), codificadas
 * num texto opaco em base64url. O cliente não interpreta nem monta cursores.
 *
 * O cursor não é assinado: trate as chaves como entrada não confiável (consulta sempre
 * parametrizada e com o escopo do usuário aplicado). Cursor ilegível, de outra versão do
 * formato ou com chave do tipo errado é `400 INVALID_CURSOR`.
 */
class PageCursor private constructor(
    val keys: List<String>,
) {
    fun encode(): String {
        val payload = VERSION + keys.joinToString(SEPARATOR) { escape(it) }
        return ENCODER.encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun string(index: Int): String = keys.getOrNull(index) ?: throw invalid()

    fun uuid(index: Int): UUID = parse(index) { UUID.fromString(it).also { uuid -> require(uuid.toString() == it) } }

    fun instant(index: Int): Instant = parse(index, Instant::parse)

    /** Uma data de negócio (`YYYY-MM-DD`), como as chaves de ordenação por data. */
    fun localDate(index: Int): LocalDate = parse(index, LocalDate::parse)

    fun long(index: Int): Long = parse(index, String::toLong)

    private fun <T> parse(
        index: Int,
        parser: (String) -> T,
    ): T =
        try {
            parser(string(index))
        } catch (_: IllegalArgumentException) {
            throw invalid()
        } catch (_: DateTimeParseException) {
            throw invalid()
        }

    override fun toString(): String = "PageCursor$keys"

    companion object {
        /** Cursores maiores que isso são recusados sem decodificar. */
        const val MAX_TOKEN_LENGTH = 1024

        fun of(vararg keys: String): PageCursor = of(keys.toList())

        fun of(keys: List<String>): PageCursor {
            require(keys.isNotEmpty()) { "O cursor precisa de ao menos uma chave" }
            return PageCursor(keys)
        }

        fun decode(token: String): PageCursor {
            val payload =
                token
                    .takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH && TOKEN.matches(it) }
                    ?.let { base64(it) }
                    ?.takeIf { it.startsWith(VERSION) }
                    ?: throw invalid()
            val keys = payload.removePrefix(VERSION).split(SEPARATOR).map { unescape(it) ?: throw invalid() }
            return PageCursor(keys)
        }

        private fun base64(token: String): String? =
            try {
                String(DECODER.decode(token), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                null
            }

        private fun invalid() =
            InvalidInputException(
                ErrorCodes.INVALID_CURSOR,
                "Cursor de paginação inválido. Recomece da primeira página.",
            )

        // Formato v1: "c1:" + chaves separadas por "|", com "~" e "|" escapados como "~~" e "~p".
        private const val VERSION = "c1:"
        private const val SEPARATOR = "|"
        private val TOKEN = Regex("[A-Za-z0-9_-]+")
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        private fun escape(key: String): String = key.replace("~", "~~").replace("|", "~p")

        private fun unescape(key: String): String? {
            val out = StringBuilder()
            var index = 0
            while (index < key.length) {
                val char = key[index]
                if (char == '~') {
                    when (key.getOrNull(index + 1)) {
                        '~' -> out.append('~')
                        'p' -> out.append('|')
                        else -> return null
                    }
                    index += 2
                } else {
                    out.append(char)
                    index++
                }
            }
            return out.toString()
        }
    }
}
