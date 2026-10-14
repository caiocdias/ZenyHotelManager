package com.zeny.hotel.shared

/**
 * Código de erro estável (RNF-13, ARCHITECTURE §7): `UPPER_SNAKE_CASE` em inglês, como
 * `ROOM_NOT_READY`. Os clientes traduzem pelo código; a mensagem em pt-BR é só um apoio.
 * Um código publicado nunca muda de significado nem de nome: renomear é mudança incompatível.
 */
@JvmInline
value class ErrorCode(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "Código de erro fora do padrão UPPER_SNAKE_CASE: '$value'" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*")
    }
}

/**
 * Códigos comuns a toda a API. Os módulos declaram os seus (`ROOM_NOT_READY`,
 * `LIMIT_EXCEEDED`…) na própria API pública, junto das exceções que os lançam.
 */
object ErrorCodes {
    /** 400: campos inválidos; o problema traz `errors[]` por campo. */
    val VALIDATION_FAILED = ErrorCode("VALIDATION_FAILED")

    /** 400: corpo ilegível (JSON quebrado) ou requisição que não chega a ser interpretada. */
    val MALFORMED_REQUEST = ErrorCode("MALFORMED_REQUEST")

    /** 400: cursor de paginação inválido, adulterado ou de outra consulta. */
    val INVALID_CURSOR = ErrorCode("INVALID_CURSOR")

    /** 401: requisição sem autenticação válida. */
    val UNAUTHENTICATED = ErrorCode("UNAUTHENTICATED")

    /** 403: autenticado, mas sem permissão. */
    val ACCESS_DENIED = ErrorCode("ACCESS_DENIED")

    /** 404: rota ou recurso inexistente. */
    val NOT_FOUND = ErrorCode("NOT_FOUND")

    /** 405: método HTTP não suportado na rota. */
    val METHOD_NOT_ALLOWED = ErrorCode("METHOD_NOT_ALLOWED")

    /** 406: nenhuma representação aceitável para o `Accept` pedido. */
    val NOT_ACCEPTABLE = ErrorCode("NOT_ACCEPTABLE")

    /** 409: conflito com o estado atual do recurso. */
    val CONFLICT = ErrorCode("CONFLICT")

    /** 412: `If-Match` não corresponde à versão atual (outra alteração chegou antes). */
    val PRECONDITION_FAILED = ErrorCode("PRECONDITION_FAILED")

    /** 413: corpo maior que o permitido. */
    val PAYLOAD_TOO_LARGE = ErrorCode("PAYLOAD_TOO_LARGE")

    /** 415: `Content-Type` não suportado. */
    val UNSUPPORTED_MEDIA_TYPE = ErrorCode("UNSUPPORTED_MEDIA_TYPE")

    /** 422: regra de negócio violada, sem código mais específico. */
    val BUSINESS_RULE_VIOLATED = ErrorCode("BUSINESS_RULE_VIOLATED")

    /** 428: alteração sem o `If-Match` obrigatório. */
    val PRECONDITION_REQUIRED = ErrorCode("PRECONDITION_REQUIRED")

    /** 400: endpoint marcado com [Idempotent] chamado sem o cabeçalho `Idempotency-Key`. */
    val IDEMPOTENCY_KEY_REQUIRED = ErrorCode("IDEMPOTENCY_KEY_REQUIRED")

    /** 400: `Idempotency-Key` vazio, longo demais (mais de 255) ou com caractere fora do ASCII visível. */
    val IDEMPOTENCY_KEY_INVALID = ErrorCode("IDEMPOTENCY_KEY_INVALID")

    /** 409: a requisição com a mesma `Idempotency-Key` ainda está em andamento. */
    val IDEMPOTENCY_KEY_IN_USE = ErrorCode("IDEMPOTENCY_KEY_IN_USE")

    /** 422: a `Idempotency-Key` já foi usada, nas últimas 24h, com outra requisição. */
    val IDEMPOTENCY_KEY_REUSED = ErrorCode("IDEMPOTENCY_KEY_REUSED")

    /** 4xx sem código mais específico (status raro, como 408 ou 410). */
    val REQUEST_REJECTED = ErrorCode("REQUEST_REJECTED")

    /** 500: erro inesperado; detalhes só no log do servidor. */
    val INTERNAL_ERROR = ErrorCode("INTERNAL_ERROR")

    /** 503: serviço temporariamente indisponível. */
    val SERVICE_UNAVAILABLE = ErrorCode("SERVICE_UNAVAILABLE")
}

/**
 * Códigos de `errors[].code` (erro por campo). As restrições Bean Validation viram o nome da
 * anotação em `UPPER_SNAKE_CASE`: `@NotBlank` → `NOT_BLANK`, `@Size` → `SIZE`.
 */
object FieldViolationCodes {
    /** Campo, parâmetro ou cabeçalho obrigatório ausente ou nulo. */
    const val REQUIRED = "REQUIRED"

    /** Valor em formato ou tipo inválido (número, data, UUID, dinheiro). */
    const val INVALID_FORMAT = "INVALID_FORMAT"

    /** Valor inválido sem código mais específico. */
    const val INVALID_VALUE = "INVALID_VALUE"
}

/** Erro de um campo da requisição (`errors[]` do problema RFC 9457); [field] é o caminho JSON (`items[0].price`). */
data class FieldViolation(
    val field: String,
    val code: String,
    val message: String,
)

/** Família do erro, que a camada web traduz para o status HTTP (ARCHITECTURE §7). */
enum class ErrorKind {
    /** 400 */
    INVALID_INPUT,

    /** 404 */
    NOT_FOUND,

    /** 409 */
    CONFLICT,

    /** 422 */
    RULE_VIOLATION,

    /** 403 */
    PERMISSION_DENIED,

    /** 412 */
    PRECONDITION_FAILED,

    /** 428 */
    PRECONDITION_REQUIRED,
}

/**
 * Raiz das exceções esperadas da aplicação, sem dependência de Spring: o domínio e a camada
 * `application` as lançam, e a camada web as converte num problema RFC 9457 com o [code], o
 * status da [kind] e a mensagem como `detail`.
 *
 * A mensagem vai para o cliente: escreva-a em pt-BR, para o usuário final, e nunca ponha nela
 * dado pessoal, segredo ou detalhe interno. Erros inesperados não são `DomainException`: viram
 * `500 INTERNAL_ERROR` com mensagem genérica.
 */
@Suppress("AbstractClassCanBeConcreteClass") // raiz da hierarquia: só as famílias abaixo são lançadas
abstract class DomainException protected constructor(
    val kind: ErrorKind,
    val code: ErrorCode,
    message: String,
    val violations: List<FieldViolation> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    override val message: String get() = super.message.orEmpty()
}

/** Entrada inválida que só a aplicação detecta (400), opcionalmente com erros por campo. */
open class InvalidInputException(
    code: ErrorCode,
    message: String,
    violations: List<FieldViolation> = emptyList(),
    cause: Throwable? = null,
) : DomainException(ErrorKind.INVALID_INPUT, code, message, violations, cause)

/** Recurso inexistente ou fora do escopo de quem pede (404). */
open class NotFoundException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.NOT_FOUND, code, message)

/** Conflito com o estado atual: transição inválida, duplicidade (409). Ex.: `ROOM_NOT_READY`. */
open class ConflictException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.CONFLICT, code, message)

/** Regra de negócio violada com a entrada bem formada (422). Ex.: `LIMIT_EXCEEDED`. */
open class BusinessRuleException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.RULE_VIOLATION, code, message)

/** Sem permissão, escopo ou aprovação para a ação (403). Ex.: `APPROVAL_REQUIRED`. */
open class PermissionDeniedException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.PERMISSION_DENIED, code, message)

/** Pré-condição da requisição condicional falhou (412). */
open class PreconditionFailedException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.PRECONDITION_FAILED, code, message)

/** Alteração sem a pré-condição obrigatória, o `If-Match` (428). */
open class PreconditionRequiredException(
    code: ErrorCode,
    message: String,
) : DomainException(ErrorKind.PRECONDITION_REQUIRED, code, message)
