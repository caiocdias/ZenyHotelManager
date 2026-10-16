package com.zeny.hotel.shared.web

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.application.sync.IncomingOfflineCommand
import com.zeny.hotel.shared.application.sync.OfflineChangesPage
import com.zeny.hotel.shared.application.sync.OfflineChangesService
import com.zeny.hotel.shared.application.sync.OfflineCommandResult
import com.zeny.hotel.shared.application.sync.OfflineCommandService
import com.zeny.hotel.shared.application.sync.OfflineCommandStatus
import com.zeny.hotel.shared.application.sync.SourcedChange
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * Sincronização do app operacional offline (ARCHITECTURE §9, D11, RNF-05): download incremental por cursor
 * e upload de comandos idempotentes por `commandId`. Exige só autenticação: o download entrega as fontes de
 * cuja permissão o usuário tem concessão, e cada handler confere a permissão do seu comando.
 */
@ApiV1Controller
@RequestMapping("/sync")
@Tag(name = "sync", description = "Sincronização offline do app operacional.")
class SyncController(
    private val changes: OfflineChangesService,
    private val commands: OfflineCommandService,
    private val jsonMapper: JsonMapper,
) {
    @GetMapping("/changes")
    @Operation(
        operationId = "listSyncChanges",
        summary = "Baixa as mudanças desde o cursor",
        description = CHANGES_DESCRIPTION,
    )
    fun changes(
        @Valid page: PageParams,
    ): SyncChangesResponse = SyncChangesResponse.of(changes.changes(page.cursor, page.limit), jsonMapper)

    @PostMapping("/commands")
    @Operation(
        operationId = "submitSyncCommands",
        summary = "Envia um lote de comandos offline",
        description = COMMANDS_DESCRIPTION,
    )
    fun commands(
        @Valid @RequestBody request: SyncCommandsRequest,
    ): SyncCommandsResponse =
        SyncCommandsResponse(
            commands
                .process(
                    request.commands.map {
                        IncomingOfflineCommand(
                            checkNotNull(it.commandId),
                            checkNotNull(it.type),
                            it.payload.orEmpty(),
                            checkNotNull(it.occurredAt),
                            checkNotNull(it.deviceClock),
                        )
                    },
                ).map(SyncCommandResultResponse::of),
        )

    private companion object {
        const val CHANGES_DESCRIPTION =
            "Sem `cursor`, o estado completo do que o usuário pode ver no escopo offline; com o `cursor` da " +
                "resposta anterior, só o que mudou desde ele, em ordem, incluindo *tombstones* (`deleted`: o " +
                "registro foi apagado, desativado ou saiu do escopo; apague-o). Com `hasMore`, peça a página " +
                "seguinte com o novo `cursor`; sem ele, guarde o `cursor` para a próxima sincronização. " +
                "`reset` indica um download completo (sem cursor, ou o usuário ou as permissões mudaram): " +
                "descarte o que tem das fontes antes de aplicar. Cada mudança traz a fonte, o id, a versão e os " +
                "dados, sem dado pessoal de hóspede (RN-13). Fonte `rooms` (exige `rooms.offline.read`): UHs " +
                "ativas com `number`, `floor`, `occupancy`, `housekeepingStatus`, `inspectionPending` e " +
                "`serviceFlag`. Cursor inválido: 400 `INVALID_CURSOR`."

        const val COMMANDS_DESCRIPTION =
            "Lote ordenado de até 100 comandos `{commandId (UUIDv7 gerado no dispositivo), type, payload, " +
                "occurredAt, deviceClock (o relógio do dispositivo no envio)}`, processados em ordem, cada um na " +
                "sua transação. Um resultado por comando: `APPLIED`, `APPLIED_WITH_WARNINGS` (com `warnings`), " +
                "`REJECTED` (com `code` e `message`; definitivo), `FAILED` (falha no servidor: reenvie) ou " +
                "`NOT_PROCESSED` (um anterior falhou: reenvie a partir dele). Um `commandId` já processado " +
                "devolve o resultado guardado (`replayed`), sem aplicar de novo; de outro usuário, " +
                "`COMMAND_ID_CONFLICT`; com outro tipo, `COMMAND_ID_REUSED`; com o instante do UUIDv7, " +
                "corrigido pela defasagem do relógio, mais antigo que a retenção (30 dias), ou cujo resultado " +
                "já saiu pela retenção (o id fica guardado por 365 dias), `COMMAND_EXPIRED`. Outras rejeições " +
                "do protocolo: `COMMAND_ID_INVALID` (não é UUIDv7, ou instante implausível), " +
                "`DEVICE_TIME_INVALID` (`deviceClock` ou `occurredAt` antes de 2020-01-01 ou mais de 366 dias à " +
                "frente do servidor), `UNKNOWN_COMMAND_TYPE` e `COMMAND_PAYLOAD_INVALID`. O `occurredAt` é " +
                "corrigido pela defasagem " +
                "do relógio (`recebimento − deviceClock`), sem passar do recebimento; acima da tolerância, o " +
                "comando é aplicado com o alerta `CLOCK_SKEW_EXCEEDED`. O endpoint não usa `Idempotency-Key`."
    }
}

@Schema(description = "Uma página do download offline.")
data class SyncChangesResponse(
    val changes: List<SyncChangeResponse>,
    @field:Schema(description = "Cursor a mandar na próxima chamada (página seguinte ou próxima sincronização).")
    val cursor: String,
    @field:Schema(description = "Há mais mudanças disponíveis agora: peça a próxima página com o `cursor`.")
    val hasMore: Boolean,
    @field:Schema(description = "Download completo: descarte o que tem das fontes antes de aplicar.")
    val reset: Boolean,
) {
    companion object {
        fun of(
            page: OfflineChangesPage,
            jsonMapper: JsonMapper,
        ) = SyncChangesResponse(
            page.changes.map { SyncChangeResponse.of(it, jsonMapper) },
            page.cursor,
            page.hasMore,
            page.reset,
        )
    }
}

@Schema(description = "Uma mudança de uma fonte offline, ou um tombstone (`deleted`).")
data class SyncChangeResponse(
    @field:Schema(description = "A fonte (`rooms`…).")
    val source: String,
    val id: UUID,
    @field:Schema(description = "A versão do registro; nula em alguns tombstones.", nullable = true)
    val version: Long?,
    @field:Schema(description = "O registro foi apagado, desativado ou saiu do escopo: apague-o.")
    val deleted: Boolean,
    @field:Schema(
        description = "Os dados do registro, no formato da fonte; nulo no tombstone.",
        nullable = true,
        type = "object",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
    )
    val data: Map<String, Any?>?,
) {
    companion object {
        fun of(
            sourced: SourcedChange,
            jsonMapper: JsonMapper,
        ) = SyncChangeResponse(
            sourced.source.name,
            sourced.change.id,
            sourced.change.version,
            sourced.change.deleted,
            sourced.change.data?.let { jsonMapper.convertValue(it, DATA_TYPE) },
        )

        private val DATA_TYPE =
            object : tools.jackson.core.type.TypeReference<Map<String, Any?>>() {}
    }
}

data class SyncCommandsRequest(
    @field:NotEmpty
    @field:Size(max = MAX_BATCH)
    @field:Valid
    val commands: List<SyncCommandRequest> = emptyList(),
) {
    companion object {
        const val MAX_BATCH = 100
    }
}

data class SyncCommandRequest(
    @field:NotNull
    @field:Schema(description = "UUIDv7 gerado no dispositivo, único por comando.")
    val commandId: UUID?,
    @field:NotBlank
    @field:Size(max = OfflineCommandType.MAX_LENGTH)
    @field:Schema(description = "O tipo do comando, `<módulo>.<nome>`.")
    val type: String?,
    @field:Schema(
        description = "Os dados do comando, no formato do tipo.",
        type = "object",
        additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
    )
    val payload: Map<String, Any?>?,
    @field:NotNull
    @field:Schema(description = "Quando aconteceu, pelo relógio do dispositivo.")
    val occurredAt: Instant?,
    @field:NotNull
    @field:Schema(description = "O relógio do dispositivo no envio do lote (para a correção da defasagem).")
    val deviceClock: Instant?,
)

data class SyncCommandsResponse(
    val results: List<SyncCommandResultResponse>,
)

data class SyncWarningResponse(
    val code: String,
    val message: String,
)

@Schema(description = "O resultado de um comando do lote, na ordem do lote.")
data class SyncCommandResultResponse(
    val commandId: UUID,
    val status: OfflineCommandStatus,
    @field:Schema(description = "Motivo estável da rejeição ou da falha.", nullable = true)
    val code: String?,
    @field:Schema(description = "Mensagem em pt-BR da rejeição ou da falha.", nullable = true)
    val message: String?,
    val warnings: List<SyncWarningResponse>,
    @field:Schema(description = "O `occurredAt` corrigido pela defasagem do relógio.", nullable = true)
    val occurredAt: Instant?,
    @field:Schema(description = "Resultado guardado de um envio anterior: não foi aplicado de novo.")
    val replayed: Boolean,
) {
    companion object {
        fun of(result: OfflineCommandResult) =
            SyncCommandResultResponse(
                result.commandId,
                result.status,
                result.code?.value,
                result.message,
                result.warnings.map { SyncWarningResponse(it.code.value, it.message) },
                result.occurredAt,
                result.replayed,
            )
    }
}
