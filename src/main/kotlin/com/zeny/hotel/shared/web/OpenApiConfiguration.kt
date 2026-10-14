package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.Cnpj
import com.zeny.hotel.shared.Cpf
import com.zeny.hotel.shared.DocumentNumber
import com.zeny.hotel.shared.IDEMPOTENCY_KEY_HEADER
import com.zeny.hotel.shared.IDEMPOTENT_REPLAYED_HEADER
import com.zeny.hotel.shared.Idempotent
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.Percent
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import io.swagger.v3.oas.models.media.MediaType as OpenApiMediaType

/**
 * Contrato OpenAPI gerado pelo springdoc (ARCHITECTURE §7, D6), versionado em
 * `api/openapi.yaml`: informações da API, os tipos de valor do kernel como `string` (como saem
 * no JSON), o problema RFC 9457 como resposta `default` de toda operação, o cabeçalho
 * `Idempotency-Key` nas operações marcadas com [Idempotent] e o access token JWT
 * (`Authorization: Bearer`) exigido por toda operação que não declare
 * `@SecurityRequirements` vazio (as públicas: login, refresh e versão).
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
                            "`ETag`/`If-Match`; `Idempotency-Key` obrigatório nas operações com efeito financeiro ou " +
                            "externo (resposta guardada por 24h); instantes em UTC (ISO-8601 com `Z`), datas " +
                            "`YYYY-MM-DD` e dinheiro como string decimal em BRL. Autenticação: " +
                            "`POST /api/v1/auth/login` devolve um access token JWT de 15 min, enviado como " +
                            "`Authorization: Bearer …`, e um refresh token rotativo da sessão do dispositivo, " +
                            "trocado em `POST /api/v1/auth/refresh`. Um `401` com `code` `UNAUTHENTICATED` pede um " +
                            "refresh; " +
                            "os demais códigos de `401` (credenciais, bloqueio, refresh) pedem um novo login.",
                    ),
            ).servers(listOf(Server().url("/").description("Este servidor")))
            .components(Components().addSecuritySchemes(BEARER_AUTH, bearerScheme()))
            .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))

    private fun bearerScheme(): SecurityScheme =
        SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .description(
                "Access token JWT (RS256) de `POST /api/v1/auth/login` ou `POST /api/v1/auth/refresh`, válido por 15 " +
                    "min. Enquanto a troca de senha estiver pendente, só `POST /api/v1/auth/password` e " +
                    "`POST /api/v1/auth/logout` respondem; o resto devolve 403 `PASSWORD_CHANGE_REQUIRED`.",
            )

    @Bean
    fun problemResponsesCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.components
                .addSchemas(FIELD_VIOLATION, fieldViolationSchema())
                .addSchemas(PROBLEM, problemSchema())
                .addParameters(IDEMPOTENCY_KEY, idempotencyKeyParameter())
                .addHeaders(IDEMPOTENT_REPLAYED, idempotentReplayedHeader())
            val problemResponse = problemResponse("Erro no formato RFC 9457, com `code` estável.")
            openApi.paths.orEmpty().values.flatMap { it.readOperations() }.forEach { operation ->
                operation.responses.addApiResponse("default", problemResponse)
            }
        }

    /**
     * Operações marcadas com [Idempotent]: o cabeçalho `Idempotency-Key` obrigatório, o
     * `Idempotent-Replayed` nas respostas de sucesso e as respostas 409 e 422 da idempotência
     * (sem trocar uma 409/422 que a operação já documente).
     */
    @Bean
    fun idempotentOperationCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            if (handlerMethod.hasMethodAnnotation(Idempotent::class.java)) {
                operation.addParametersItem(Parameter().`$ref`(ref("parameters", IDEMPOTENCY_KEY)))
                operation.responses
                    .orEmpty()
                    .filterKeys { it.startsWith("2") }
                    .values
                    .forEach {
                        it.addHeaderObject(
                            IDEMPOTENT_REPLAYED_HEADER,
                            Header().`$ref`(ref("headers", IDEMPOTENT_REPLAYED)),
                        )
                    }
                operation.responses.putIfAbsent(
                    "409",
                    problemResponse("`IDEMPOTENCY_KEY_IN_USE`: a requisição com esta chave ainda está em andamento."),
                )
                operation.responses.putIfAbsent(
                    "422",
                    problemResponse("`IDEMPOTENCY_KEY_REUSED`: a chave já foi usada com outra requisição."),
                )
            }
            operation
        }

    private fun problemResponse(description: String): ApiResponse =
        ApiResponse()
            .description(description)
            .content(
                Content().addMediaType(
                    MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    OpenApiMediaType().schema(Schema<Any>().`$ref`(ref(PROBLEM))),
                ),
            )

    private fun idempotencyKeyParameter(): Parameter =
        HeaderParameter()
            .name(IDEMPOTENCY_KEY_HEADER)
            .required(true)
            .description(
                "Identificador único da operação, gerado pelo cliente (um UUID). Repetir a mesma requisição com a " +
                    "mesma chave em até 24h devolve a resposta guardada, sem executar de novo (com o cabeçalho " +
                    "`Idempotent-Replayed: true`). Sem chave: 400 `IDEMPOTENCY_KEY_REQUIRED`; malformada: 400 " +
                    "`IDEMPOTENCY_KEY_INVALID`; em andamento: 409 `IDEMPOTENCY_KEY_IN_USE`; usada com outra " +
                    "requisição: 422 `IDEMPOTENCY_KEY_REUSED`. A chave vale por usuário.",
            ).schema(
                StringSchema()
                    .minLength(1)
                    .maxLength(MAX_IDEMPOTENCY_KEY_LENGTH)
                    .pattern("^[\\x21-\\x7E]+$")
                    .example("0199d6b8-1f2e-7a3b-8c4d-5e6f7a8b9c0d"),
            )

    private fun idempotentReplayedHeader(): Header =
        Header()
            .description(
                "`true` quando a resposta é a guardada de uma requisição anterior com a mesma `Idempotency-Key`.",
            ).schema(StringSchema().addEnumItem("true"))

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
                            "MALFORMED_REQUEST, INVALID_CURSOR, UNAUTHENTICATED, ACCESS_DENIED, " +
                            "PASSWORD_CHANGE_REQUIRED, NOT_FOUND, " +
                            "METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, CONFLICT, PRECONDITION_FAILED, PAYLOAD_TOO_LARGE, " +
                            "UNSUPPORTED_MEDIA_TYPE, BUSINESS_RULE_VIOLATED, PRECONDITION_REQUIRED, " +
                            "IDEMPOTENCY_KEY_REQUIRED, IDEMPOTENCY_KEY_INVALID, IDEMPOTENCY_KEY_IN_USE, " +
                            "IDEMPOTENCY_KEY_REUSED, REQUEST_REJECTED, INTERNAL_ERROR, SERVICE_UNAVAILABLE. Cada " +
                            "módulo acrescenta os seus.",
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
        const val BEARER_AUTH = "bearerAuth"
        const val FIELD_VIOLATION = "FieldViolation"
        const val IDEMPOTENCY_KEY = "IdempotencyKey"
        const val IDEMPOTENT_REPLAYED = "IdempotentReplayed"
        const val MAX_IDEMPOTENCY_KEY_LENGTH = 255

        fun ref(schema: String) = ref("schemas", schema)

        fun ref(
            section: String,
            name: String,
        ) = "#/components/$section/$name"

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
