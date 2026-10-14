package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.FieldViolation
import com.zeny.hotel.shared.FieldViolationCodes
import org.springframework.context.MessageSourceResolvable
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.method.annotation.HandlerMethodValidationException
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.InvalidNullException

/**
 * Converte os erros de validação e de leitura do Spring e do Jackson em `errors[]` com código
 * estável por campo: o nome da restrição Bean Validation em `UPPER_SNAKE_CASE` (`NOT_BLANK`,
 * `SIZE`…), `REQUIRED` para ausente ou nulo e `INVALID_FORMAT` para valor que não converte.
 * A mensagem é a da restrição (em pt-BR pelo `spring.web.locale`) ou uma genérica; nunca a
 * mensagem técnica do Spring ou do Jackson.
 */
internal object FieldViolations {
    private const val TYPE_MISMATCH = "typeMismatch"
    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")

    fun required(field: String) = FieldViolation(field, FieldViolationCodes.REQUIRED, "campo obrigatório")

    fun invalidFormat(field: String) =
        FieldViolation(field, FieldViolationCodes.INVALID_FORMAT, "valor em formato inválido")

    /** Erro de binding ou de restrição num objeto (`@Valid @RequestBody`, `@Valid` em parâmetros de query). */
    fun of(error: ObjectError): FieldViolation {
        val field = (error as? FieldError)?.field ?: error.objectName
        return if (error is FieldError && error.isBindingFailure) invalidFormat(field) else of(field, error)
    }

    /** Erros da validação por método (restrição direto no parâmetro do controller). */
    fun of(ex: HandlerMethodValidationException): List<FieldViolation> =
        ex.parameterValidationResults.flatMap { result ->
            val parameter = result.methodParameter
            val name =
                parameter.getParameterAnnotation(RequestParam::class.java)?.name?.ifBlank { null }
                    ?: parameter.parameterName
                    ?: "arg${parameter.parameterIndex}"
            result.resolvableErrors.map { error -> if (error is FieldError) of(error) else of(name, error) }
        }

    /**
     * O campo do corpo que o Jackson não conseguiu ler, ou `null` se o problema é o JSON em si
     * (sintaxe, tipo do documento inteiro).
     */
    fun of(ex: HttpMessageNotReadableException): FieldViolation? {
        val databind = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<DatabindException>().firstOrNull()
        val field = databind?.let(::pathOf)
        return when {
            field.isNullOrEmpty() -> null
            databind is InvalidNullException -> required(field)
            else -> invalidFormat(field)
        }
    }

    private fun of(
        field: String,
        error: MessageSourceResolvable,
    ): FieldViolation {
        val constraint = (error as? ObjectError)?.code ?: error.codes?.lastOrNull()
        return when (constraint) {
            null -> FieldViolation(field, FieldViolationCodes.INVALID_VALUE, error.defaultMessage ?: "valor inválido")
            TYPE_MISMATCH -> invalidFormat(field)
            else -> FieldViolation(field, upperSnake(constraint), error.defaultMessage ?: "valor inválido")
        }
    }

    /** Caminho JSON do campo a partir das referências do Jackson: `items[0].price`. */
    private fun pathOf(ex: DatabindException): String {
        val path = StringBuilder()
        ex.path.forEach { reference ->
            val property = reference.propertyName
            if (property != null) {
                if (path.isNotEmpty()) path.append('.')
                path.append(property)
            } else if (reference.index >= 0) {
                path.append('[').append(reference.index).append(']')
            }
        }
        return path.toString()
    }

    private fun upperSnake(name: String): String = name.replace(CAMEL_HUMP, "$1_$2").uppercase()
}
