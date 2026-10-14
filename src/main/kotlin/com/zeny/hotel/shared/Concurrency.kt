package com.zeny.hotel.shared

/**
 * Concorrência otimista na API (ARCHITECTURE §5 e §7): a versão do agregado (`@Version`) sai
 * como `ETag` nas leituras e volta no `If-Match` das alterações; versão diferente da atual é
 * `412 PRECONDITION_FAILED`, e alteração sem `If-Match` é `428 PRECONDITION_REQUIRED`.
 *
 * ```kotlin
 * // leitura
 * ResponseEntity.ok().eTag(ETags.of(room.version)).body(dto)
 * // alteração: o controller lê o cabeçalho e o caso de uso confere a versão
 * val expected = ETags.expectedVersion(ifMatch)          // 428 se faltar
 * requireCurrentVersion(expected, room.version)          // 412 se mudou
 * ```
 */
object ETags {
    /** A versão como etag forte: `"7"`. */
    fun of(version: Long): String = "\"$version\""

    /**
     * A versão esperada pelo cliente, lida do `If-Match`. Ausente ou em branco: 428. Uma etag
     * fraca nunca casa em `If-Match` (RFC 9110 §13.1.1), e `*` ou uma lista não dizem qual
     * versão o cliente viu; tudo o que não for uma única etag forte com a versão é 412.
     */
    fun expectedVersion(ifMatch: String?): Long {
        if (ifMatch.isNullOrBlank()) {
            throw PreconditionRequiredException(
                ErrorCodes.PRECONDITION_REQUIRED,
                "Envie o cabeçalho If-Match com a ETag da última leitura do recurso.",
            )
        }
        return STRONG_VERSION_TAG
            .matchEntire(ifMatch.trim())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: throw PreconditionFailedException(
                ErrorCodes.PRECONDITION_FAILED,
                "O If-Match não corresponde à versão atual do recurso. Leia o recurso de novo.",
            )
    }

    private val STRONG_VERSION_TAG = Regex("\"(\\d{1,19})\"")
}

/** Confere a versão esperada pelo cliente contra a atual do agregado; diferente é [StaleVersionException]. */
fun requireCurrentVersion(
    expected: Long,
    current: Long,
) {
    if (expected != current) throw StaleVersionException(expected, current)
}

/** O recurso mudou desde a leitura do cliente (412): outra alteração chegou antes. */
class StaleVersionException(
    val expected: Long,
    val current: Long,
) : PreconditionFailedException(
        ErrorCodes.PRECONDITION_FAILED,
        "O recurso foi alterado por outra requisição. Leia de novo e refaça a alteração.",
    )
