package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

/** Informações públicas do servidor, para o cliente conferir compatibilidade antes de autenticar. */
@ApiV1Controller
@RequestMapping("/meta")
@Tag(name = "meta", description = "Informações sobre o servidor e a API.")
class MetaController(
    @Value("\${spring.application.name}") private val application: String,
    buildProperties: ObjectProvider<BuildProperties>,
) {
    private val version: String = buildProperties.ifAvailable?.version ?: "dev"

    @GetMapping("/version")
    @SecurityRequirements
    @Operation(
        operationId = "getServerVersion",
        summary = "Versão do servidor e da API",
        description = "Público, sem autenticação. A versão da API muda só com mudança incompatível (/api/v2).",
    )
    fun version(): ServerVersionResponse = ServerVersionResponse(application, version, API_VERSION)

    private companion object {
        const val API_VERSION = "v1"
    }
}

data class ServerVersionResponse(
    @field:Schema(description = "Nome da aplicação.", example = "zeny-hotel-manager")
    val application: String,
    @field:Schema(description = "Versão do servidor (build).", example = "1.4.0")
    val version: String,
    @field:Schema(description = "Versão da API, igual ao prefixo das rotas.", example = "v1")
    val apiVersion: String,
)
