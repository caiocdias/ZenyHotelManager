package com.zeny.hotel.shared

/** Cabeçalho com a chave de idempotência enviada pelo cliente (ARCHITECTURE §7). */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** Cabeçalho `true` nas respostas repetidas a partir da guardada (o efeito não rodou de novo). */
const val IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed"

/**
 * Marca um endpoint cujo efeito não pode acontecer em dobro (ARCHITECTURE §7): pagamentos,
 * lançamentos, emissão fiscal e chamadas externas. A requisição passa a exigir o cabeçalho
 * [IDEMPOTENCY_KEY_HEADER] (até 255 caracteres ASCII visíveis; o cliente gera um UUID por
 * operação) e a infraestrutura do `shared` garante, por usuário autenticado e chave:
 *
 * - sem chave: `400 IDEMPOTENCY_KEY_REQUIRED`; chave malformada: `400 IDEMPOTENCY_KEY_INVALID`;
 * - primeira requisição: executa e guarda a resposta inteira (status, corpo e cabeçalhos como
 *   `Location` e `ETag`) por 24h, **inclusive respostas de erro**, que se repetem como erro;
 * - mesma chave e mesma requisição (método, caminho, query, `If-Match` e corpo, byte a byte):
 *   devolve a resposta guardada, com [IDEMPOTENT_REPLAYED_HEADER] `true`, sem executar de novo;
 * - mesma chave com outra requisição: `422 IDEMPOTENCY_KEY_REUSED`;
 * - mesma chave enquanto a primeira ainda executa: `409 IDEMPOTENCY_KEY_IN_USE`.
 *
 * ```kotlin
 * @PostMapping("/{id}/payments")
 * @Idempotent
 * @Operation(operationId = "registerPayment")
 * fun register(...): ResponseEntity<PaymentResponse>
 * ```
 *
 * O handler precisa ser síncrono e o endpoint, autenticado. O contrato OpenAPI ganha o
 * cabeçalho obrigatório e as respostas 409/422 sozinho. Não se aplica a comandos offline, que
 * têm idempotência própria por `commandId` (M2).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Idempotent
