package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import io.swagger.v3.oas.models.media.MediaType as OpenApiMediaType

/**
 * Contrato OpenAPI gerado pelo springdoc (ARCHITECTURE §7, D6), versionado em
 * `api/openapi.yaml`: informações da API, os tipos de valor do kernel como `string` (como saem
 * no JSON) e o problema RFC 9457 como resposta `default` de toda operação.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun zenyOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Zeny Hotel Manager API")
                    .version("v1")
                    .description(
                        "API do servidor do Zeny Hotel Manager. Erros em RFC 9457 (application/problem+json) com " +
                            "`code` estável; paginação por cursor (`cursor`/`limit`); concorrência otimista com " +
                            "`ETag`/`If-Match`; instantes em UTC (ISO-8601 com `Z`), datas `YYYY-MM-DD` e dinheiro " +
                            "como string decimal em BRL.",
                    ),
            ).servers(listOf(Server().url("/").description("Este servidor")))

    @Bean
    fun problemResponsesCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.components
                .addSchemas(FIELD_VIOLATION, fieldViolationSchema())
                .addSchemas(PROBLEM, problemSchema())
            val problemResponse =
                ApiResponse()
                    .description("Erro no formato RFC 9457, com `code` estável.")
                    .content(
                        Content().addMediaType(
                            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            OpenApiMediaType().schema(Schema<Any>().`$ref`(ref(PROBLEM))),
                        ),
                    )
            openApi.paths.orEmpty().values.flatMap { it.readOperations() }.forEach { operation ->
                operation.responses.addApiResponse("default", problemResponse)
            }
        }

    private fun problemSchema(): Schema<Any> =
        ObjectSchema()
            .description("Problema RFC 9457 (application/problem+json).")
            .addProperty(
                "type",
                StringSchema()
                    .format("uri")
                    .description("Tipo do problema; ausente equivale a about:blank.")
                    .example("about:blank"),
            ).addProperty(
                "title",
                StringSchema().description("Frase do status HTTP, em pt-BR.").example("Requisição inválida"),
            ).addProperty("status", IntegerSchema().description("Status HTTP.").example(HttpStatus.BAD_REQUEST.value()))
            .addProperty("detail", StringSchema().description("Explicação em pt-BR, para o usuário."))
            .addProperty("instance", StringSchema().format("uri").description("Caminho da requisição."))
            .addProperty(
                "code",
                StringSchema()
                    .pattern("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")
                    .description(
                        "Código estável do erro, para o cliente traduzir. Comuns: VALIDATION_FAILED, " +
                            "MALFORMED_REQUEST, INVALID_CURSOR, UNAUTHENTICATED, ACCESS_DENIED, NOT_FOUND, " +
                            "METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, CONFLICT, PRECONDITION_FAILED, PAYLOAD_TOO_LARGE, " +
                            "UNSUPPORTED_MEDIA_TYPE, BUSINESS_RULE_VIOLATED, PRECONDITION_REQUIRED, " +
                            "REQUEST_REJECTED, INTERNAL_ERROR, SERVICE_UNAVAILABLE. Cada módulo acrescenta os seus.",
                    ).example("VALIDATION_FAILED"),
            ).addProperty(
                "errors",
                ArraySchema()
                    .items(Schema<Any>().`$ref`(ref(FIELD_VIOLATION)))
                    .description("Erros por campo; presente nos erros de validação."),
            ).apply { required = listOf("title", "status", "code") }

    private fun fieldViolationSchema(): Schema<Any> =
        ObjectSchema()
            .description("Erro de um campo da requisição.")
            .addProperty(
                "field",
                StringSchema().description("Caminho do campo no JSON ou nome do parâmetro.").example("price"),
            ).addProperty(
                "code",
                StringSchema()
                    .description(
                        "Código estável: REQUIRED, INVALID_FORMAT, INVALID_VALUE ou o nome da restrição em " +
                            "UPPER_SNAKE_CASE (NOT_BLANK, SIZE, MIN, MAX, PATTERN etc.).",
                    ).example("NOT_BLANK"),
            ).addProperty(
                "message",
                StringSchema().description("Mensagem em pt-BR.").example("não deve estar em branco"),
            ).apply { required = listOf("field", "code", "message") }

    private companion object {
        const val PROBLEM = "Problem"
        const val FIELD_VIOLATION = "FieldViolation"

        fun ref(schema: String) = "#/components/schemas/$schema"

        init {
            // Os tipos de valor do kernel saem no JSON como texto (@JsonValue); o contrato tem de dizer o mesmo.
            SpringDocUtils
                .getConfig()
                .replaceWithSchema(
                    Money::class.java,
                    decimalString(
                        "Valor em reais (BRL) como decimal com até 2 casas.",
                        "^-?\\d+(\\.\\d{1,2})?$",
                        "123.45",
                    ),
                ).replaceWithSchema(
                    Percent::class.java,
                    decimalString(
                        "Percentual em pontos (12.5 = 12,5%), até 4 casas.",
                        "^-?\\d{1,3}(\\.\\d{1,4})?$",
                        "12.5",
                    ),
                ).replaceWithSchema(
                    Cpf::class.java,
                    StringSchema().description("CPF, só os 11 dígitos.").pattern("^\\d{11}$").example("52998224725"),
                ).replaceWithSchema(
                    Cnpj::class.java,
                    StringSchema()
                        .description("CNPJ (numérico ou alfanumérico), 14 caracteres sem pontuação.")
                        .pattern("^[0-9A-Z]{12}\\d{2}$")
                        .example("12ABC34501DE35"),
                ).replaceWithSchema(
                    DocumentNumber::class.java,
                    StringSchema()
                        .description("Número legível: prefixo, ano e sequência.")
                        .pattern("^[A-Z]{1,5}-\\d{4}-\\d{6,}$")
                        .example("R-2026-000123"),
                )
        }

        private fun decimalString(
            description: String,
            pattern: String,
            example: String,
        ): Schema<*> =
            StringSchema()
                .format("decimal")
                .description(description)
                .pattern(pattern)
                .example(example)
    }
}
