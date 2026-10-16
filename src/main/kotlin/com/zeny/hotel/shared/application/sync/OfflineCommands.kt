package com.zeny.hotel.shared.application.sync

import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.DomainException
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandType
import com.zeny.hotel.shared.OfflineCommandWarning
import com.zeny.hotel.shared.OfflineSyncCodes
import com.zeny.hotel.shared.UserId
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Configuração da sincronização offline (`zeny.sync.*`).
 *
 * @property clockTolerance defasagem do relógio do dispositivo acima da qual o comando é "aplicado com
 *   alerta" (`CLOCK_SKEW_EXCEEDED`); o horário é corrigido de qualquer forma (RN-22).
 * @property commandRetention por quanto tempo o resultado de cada `commandId` fica guardado, contado de
 *   [CommandRetention.retainFrom]; um `commandId` cujo instante (UUIDv7, pelo relógio do aparelho) corrigido
 *   pela defasagem é mais antigo que isso é recusado (`COMMAND_EXPIRED`).
 * @property commandIdRetention por quanto tempo o `commandId` fica guardado, também contado de
 *   [CommandRetention.retainFrom]: passada a [commandRetention], o resultado é esvaziado (ficam o id, o usuário,
 *   o tipo, `processed_at` e `retain_from`) e o reenvio dele é `COMMAND_EXPIRED`, qualquer que seja o relógio do
 *   aparelho; passada esta, a linha é apagada. Maior que [commandRetention].
 * @property maxDeviceClockAhead o quanto o `deviceClock`, o `occurredAt` e o instante do `commandId` podem
 *   estar à frente do servidor; além disso, ou antes de [EARLIEST_DEVICE_TIME], o comando é recusado
 *   (`DEVICE_TIME_INVALID` ou `COMMAND_ID_INVALID`).
 */
@ConfigurationProperties("zeny.sync")
class OfflineSyncProperties(
    val clockTolerance: Duration,
    val commandRetention: Duration,
    val commandIdRetention: Duration,
    val maxDeviceClockAhead: Duration,
) {
    init {
        require(!clockTolerance.isNegative) { "zeny.sync.clock-tolerance não pode ser negativa" }
        require(commandRetention >= MIN_RETENTION) { "zeny.sync.command-retention deve ser de ao menos 1 dia" }
        require(commandIdRetention > commandRetention) {
            "zeny.sync.command-id-retention deve ser maior que zeny.sync.command-retention"
        }
        require(!maxDeviceClockAhead.isNegative && maxDeviceClockAhead <= MAX_AHEAD) {
            "zeny.sync.max-device-clock-ahead deve ficar entre zero e $MAX_AHEAD"
        }
    }

    companion object {
        /** Antes disto, um horário do aparelho não é plausível (o sistema não existia). */
        val EARLIEST_DEVICE_TIME: Instant = Instant.parse("2020-01-01T00:00:00Z")

        private val MIN_RETENTION: Duration = Duration.ofDays(1)
        private val MAX_AHEAD: Duration = Duration.ofDays(3660)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OfflineSyncProperties::class)
class OfflineSyncConfiguration

/** Situação de um comando no resultado do lote. */
enum class OfflineCommandStatus {
    /** Aplicado. */
    APPLIED,

    /** Aplicado, com alertas (relógio defasado, tarefa reatribuída…). */
    APPLIED_WITH_WARNINGS,

    /** Rejeitado com motivo (`code`); definitivo: a repetição devolve a mesma rejeição. */
    REJECTED,

    /** Falha inesperada no servidor: nada aplicado nem guardado; reenvie. */
    FAILED,

    /** Não processado porque um comando anterior do lote falhou; reenvie. */
    NOT_PROCESSED,
}

/** Um comando como chegou no lote. */
data class IncomingOfflineCommand(
    val commandId: UUID,
    val type: String,
    val payload: Map<String, Any?>,
    val occurredAt: Instant,
    val deviceClock: Instant,
)

/** O resultado de um comando do lote. [replayed]: devolvido do guardado, sem aplicar de novo. */
data class OfflineCommandResult(
    val commandId: UUID,
    val status: OfflineCommandStatus,
    val code: ErrorCode? = null,
    val message: String? = null,
    val warnings: List<OfflineCommandWarning> = emptyList(),
    val occurredAt: Instant? = null,
    val replayed: Boolean = false,
)

/** O resultado guardado de um `commandId`. */
data class StoredOfflineCommand(
    val commandId: UUID,
    val userId: UserId,
    val type: String,
    val status: OfflineCommandStatus,
    val code: ErrorCode?,
    val message: String?,
    val warnings: List<OfflineCommandWarning>,
    val occurredAt: Instant?,
    /** O resultado já saiu pela retenção: só o id, o usuário e o tipo continuam guardados. */
    val purged: Boolean = false,
)

/** Quantos resultados saíram pela retenção ([expiredResults]) e quantos ids foram apagados ([removedIds]). */
data class OfflineCommandPurge(
    val expiredResults: Int,
    val removedIds: Int,
)

/**
 * Porta da tabela `shared.offline_command`. As escritas rodam na transação corrente (a do comando, para o
 * resultado ser gravado junto do efeito).
 */
interface OfflineCommandRecords {
    fun find(commandId: UUID): StoredOfflineCommand?

    /**
     * Reserva o `commandId` na transação corrente (`INSERT … ON CONFLICT DO NOTHING`). Com outra transação
     * gravando o mesmo id, espera ela terminar; `false` se ele já existe (o resultado está em [find]).
     */
    fun reserve(
        commandId: UUID,
        userId: UserId,
        type: String,
        occurredAt: Instant,
        retention: CommandRetention,
    ): Boolean

    /** Conclui a reserva da transação corrente com o resultado do handler. */
    fun complete(
        commandId: UUID,
        status: OfflineCommandStatus,
        warnings: List<OfflineCommandWarning>,
    )

    /** Grava a rejeição (`ON CONFLICT DO NOTHING`); `false` se o id já tem resultado. */
    fun insertRejected(
        record: StoredOfflineCommand,
        retention: CommandRetention,
    ): Boolean

    /**
     * Esvazia o resultado dos comandos cujo início da retenção é anterior a [instant]: a linha fica só com o
     * id, o usuário e o tipo. Devolve quantos.
     */
    fun expireResultsRetainedBefore(instant: Instant): Int

    /** Apaga os ids (já sem resultado) cujo início da retenção é anterior a [instant]; devolve quantos. */
    fun deleteIdsRetainedBefore(instant: Instant): Int
}

/**
 * Quando o resultado de um `commandId` foi gravado ([processedAt]) e de quando contam as retenções dele
 * ([retainFrom]): o mais tardio entre o processamento e o instante do `commandId`, cru e corrigido pela
 * defasagem. O expurgo esvazia o resultado depois de `zeny.sync.command-retention` a partir de [retainFrom]
 * (o reenvio do id passa a ser `COMMAND_EXPIRED`, qualquer que seja o relógio do aparelho) e só apaga a linha
 * depois de `zeny.sync.command-id-retention`. Como um `commandId` desconhecido cujo instante corrigido passou da
 * retenção também é recusado, o expurgo não deixa o comando ser aplicado de novo, mesmo com o id gerado por um
 * relógio adiantado.
 */
data class CommandRetention(
    val processedAt: Instant,
    val retainFrom: Instant,
)

/**
 * O upload de comandos offline (`POST /api/v1/sync/commands`, ARCHITECTURE §9 e §6.2, D11, RNF-05, RN-22). O
 * lote é processado em ordem, cada comando na sua transação:
 *
 * 1. `commandId` que não é UUIDv7: `REJECTED COMMAND_ID_INVALID`;
 * 2. `commandId` já guardado, qualquer que seja o relógio do aparelho: de outro usuário,
 *    `REJECTED COMMAND_ID_CONFLICT`; com o resultado esvaziado pela retenção, `REJECTED COMMAND_EXPIRED`; com
 *    outro tipo, `REJECTED COMMAND_ID_REUSED`; senão, o resultado guardado (`replayed`), sem aplicar de novo;
 * 3. só para um id desconhecido, a faixa plausível (de 2020-01-01 ao recebimento mais
 *    `zeny.sync.max-device-clock-ahead`): `deviceClock` ou `occurredAt` fora dela, `REJECTED DEVICE_TIME_INVALID`;
 *    o instante do `commandId` fora dela, `REJECTED COMMAND_ID_INVALID`. Nenhuma conta com os horários roda
 *    antes desta conferência;
 * 4. instante do `commandId` (UUIDv7 gerado no aparelho) corrigido pela defasagem do relógio, mais antigo que a
 *    retenção: `REJECTED COMMAND_EXPIRED` (um aparelho atrasado não perde comandos novos; um adiantado não
 *    reaplica depois do expurgo, porque o resultado fica guardado até a retenção passar do instante do id);
 * 5. tipo sem handler: `REJECTED UNKNOWN_COMMAND_TYPE`; payload que não converte ou não passa na Bean
 *    Validation: `REJECTED COMMAND_PAYLOAD_INVALID` (essas recusas, de 1 a 5, não ficam guardadas);
 * 6. o `occurredAt` é corrigido pela defasagem (`recebimento − deviceClock`), sem passar do recebimento;
 *    acima de `zeny.sync.clock-tolerance`, o resultado leva o alerta `CLOCK_SKEW_EXCEEDED`;
 * 7. numa transação: reserva o `commandId` (a repetição simultânea espera e devolve o resultado da
 *    primeira), chama o handler e grava o resultado. Uma [DomainException] desfaz o efeito e grava a
 *    rejeição numa transação nova; outra exceção é `FAILED`, sem nada gravado, e os comandos seguintes do
 *    lote voltam `NOT_PROCESSED`, para o dispositivo reenviar na ordem.
 *
 * Uma rejeição não interrompe o lote. A gravação do resultado é técnica (como a chave de idempotência); a
 * escrita do handler é auditada por ele.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList") // os passos do protocolo em métodos curtos; os beans deles
class OfflineCommandService(
    handlers: ObjectProvider<OfflineCommandHandler<*>>,
    private val records: OfflineCommandRecords,
    private val currentActor: CurrentActor,
    private val jsonMapper: JsonMapper,
    private val validator: Validator,
    private val clock: Clock,
    private val properties: OfflineSyncProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlers: Map<String, OfflineCommandHandler<*>> = catalog(handlers.orderedStream().toList())
    private val transactions = TransactionTemplate(transactionManager)

    fun process(commands: List<IncomingOfflineCommand>): List<OfflineCommandResult> {
        val user = currentActor.current().userId
        val receivedAt = clock.instant()
        val results = ArrayList<OfflineCommandResult>(commands.size)
        var failed = false
        for (command in commands) {
            val result =
                if (failed) {
                    OfflineCommandResult(
                        command.commandId,
                        OfflineCommandStatus.NOT_PROCESSED,
                        OfflineSyncCodes.COMMAND_NOT_PROCESSED,
                        "Não processado porque um comando anterior falhou. Reenvie a partir dele.",
                    )
                } else {
                    processOne(command, user, receivedAt)
                }
            failed = failed || result.status == OfflineCommandStatus.FAILED
            results += result
        }
        return results
    }

    /**
     * Expurgo (job com ShedLock): esvazia os resultados cuja retenção passou e apaga os ids cuja retenção de id
     * passou.
     */
    fun purgeExpired(): OfflineCommandPurge =
        transactions.execute {
            val now = clock.instant()
            OfflineCommandPurge(
                expiredResults = records.expireResultsRetainedBefore(now.minus(properties.commandRetention)),
                removedIds = records.deleteIdsRetainedBefore(now.minus(properties.commandIdRetention)),
            )
        }

    private fun processOne(
        command: IncomingOfflineCommand,
        user: UserId,
        receivedAt: Instant,
    ): OfflineCommandResult {
        val id = command.commandId
        val handler = handlers[command.type]
        val payload = handler?.let { payloadOf(command, it) }
        // Um id conhecido responde pelo que está guardado, qualquer que seja o relógio do aparelho; os horários
        // só são conferidos (e só depois usados em contas) para um id desconhecido.
        val refusal =
            knownOrImplausible(command, user, receivedAt)
                ?: protocolRefusal(id, skewOf(command, receivedAt), handler, payload)
        if (refusal != null || handler == null || payload == null) {
            return checkNotNull(refusal) { "Comando $id sem handler ou payload e sem recusa" }
        }

        // A defasagem do relógio do aparelho corrige o occurredAt e também o instante do commandId (UUIDv7
        // gerado no aparelho), que decide a expiração. Daqui em diante os horários estão na faixa plausível.
        val skew = skewOf(command, receivedAt)

        // Em microssegundos, a precisão do banco: a repetição devolve exatamente o mesmo instante.
        val occurredAt = minOf(command.occurredAt.plus(skew), receivedAt).truncatedTo(ChronoUnit.MICROS)
        val clockWarnings = if (skew.abs() > properties.clockTolerance) listOf(clockWarning(skew)) else emptyList()
        val retention = CommandRetention(receivedAt, maxOf(receivedAt, idInstant(id), idInstant(id).plus(skew)))
        val accepted =
            AcceptedCommand(id, command.type, user, occurredAt, command.occurredAt, clockWarnings, retention)
        return try {
            apply(accepted, handler, payload)
        } catch (e: DomainException) {
            reject(accepted, e, command)
        } catch (
            // Falha inesperada: nada guardado, o dispositivo reenvia.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Falha inesperada ao aplicar o comando offline {} ({})", id, command.type, e)
            OfflineCommandResult(
                id,
                OfflineCommandStatus.FAILED,
                OfflineSyncCodes.COMMAND_FAILED,
                "Não foi possível aplicar o comando agora. Reenvie.",
            )
        }
    }

    /**
     * Primeiro o formato do id; depois o que está guardado (resultado, id esvaziado ou de outro usuário), sem
     * olhar os horários; só para um id desconhecido, a faixa plausível dos horários do aparelho e do instante do
     * id. Essas recusas não ficam guardadas.
     */
    private fun knownOrImplausible(
        command: IncomingOfflineCommand,
        user: UserId,
        receivedAt: Instant,
    ): OfflineCommandResult? {
        val id = command.commandId
        if (id.version() != UUID_VERSION_7) {
            return rejected(id, OfflineSyncCodes.COMMAND_ID_INVALID, "O id do comando precisa ser um UUIDv7.")
        }
        return records.find(id)?.let { replay(it, command, user) } ?: implausibleTime(command, receivedAt)
    }

    /** Os horários do aparelho e o instante do id fora da faixa plausível. */
    private fun implausibleTime(
        command: IncomingOfflineCommand,
        receivedAt: Instant,
    ): OfflineCommandResult? {
        val id = command.commandId
        return when {
            !plausible(command.deviceClock, receivedAt) || !plausible(command.occurredAt, receivedAt) -> {
                rejected(id, OfflineSyncCodes.DEVICE_TIME_INVALID, "O relógio do dispositivo está fora do plausível.")
            }

            !plausible(idInstant(id), receivedAt) -> {
                rejected(id, OfflineSyncCodes.COMMAND_ID_INVALID, "O instante do id do comando não é plausível.")
            }

            else -> {
                null
            }
        }
    }

    /** As recusas do protocolo para um `commandId` ainda sem resultado (não ficam guardadas). */
    private fun protocolRefusal(
        id: UUID,
        skew: Duration,
        handler: OfflineCommandHandler<*>?,
        payload: Any?,
    ): OfflineCommandResult? =
        when {
            idInstant(id).plus(skew) < retentionLimit() -> {
                expired(id)
            }

            handler == null -> {
                rejected(id, OfflineSyncCodes.UNKNOWN_COMMAND_TYPE, "Tipo de comando desconhecido.")
            }

            payload == null -> {
                rejected(id, OfflineSyncCodes.COMMAND_PAYLOAD_INVALID, "Os dados do comando são inválidos.")
            }

            else -> {
                null
            }
        }

    private fun apply(
        accepted: AcceptedCommand,
        handler: OfflineCommandHandler<*>,
        payload: Any,
    ): OfflineCommandResult {
        val outcome =
            transactions.execute {
                val reserved =
                    records.reserve(accepted.id, accepted.user, accepted.type, accepted.occurredAt, accepted.retention)
                if (!reserved) {
                    null
                } else {
                    val result = invoke(handler, accepted, payload)
                    val warnings = accepted.clockWarnings + result.warnings
                    val status =
                        when {
                            warnings.isEmpty() -> OfflineCommandStatus.APPLIED
                            else -> OfflineCommandStatus.APPLIED_WITH_WARNINGS
                        }
                    records.complete(accepted.id, status, warnings)
                    OfflineCommandResult(accepted.id, status, warnings = warnings, occurredAt = accepted.occurredAt)
                }
            }
        // Outra transação gravou o mesmo id antes (e esta esperou o commit dela): devolve o que ela gravou.
        return outcome ?: storedResultOf(accepted)
    }

    private fun reject(
        accepted: AcceptedCommand,
        rejection: DomainException,
        command: IncomingOfflineCommand,
    ): OfflineCommandResult {
        val record =
            StoredOfflineCommand(
                accepted.id,
                accepted.user,
                accepted.type,
                OfflineCommandStatus.REJECTED,
                rejection.code,
                rejection.message.take(MAX_MESSAGE),
                emptyList(),
                accepted.occurredAt,
            )
        val inserted = transactions.execute { records.insertRejected(record, accepted.retention) }
        if (!inserted) return records.find(accepted.id)?.let { replay(it, command, accepted.user) } ?: toResult(record)
        return toResult(record)
    }

    private fun storedResultOf(accepted: AcceptedCommand): OfflineCommandResult {
        val stored = checkNotNull(records.find(accepted.id)) { "O comando ${accepted.id} sumiu depois do conflito" }
        return when {
            stored.userId != accepted.user -> conflict(accepted.id)
            stored.purged -> expired(accepted.id)
            else -> replayOf(stored, accepted.type)
        }
    }

    private fun replay(
        stored: StoredOfflineCommand,
        command: IncomingOfflineCommand,
        user: UserId,
    ): OfflineCommandResult =
        when {
            stored.userId != user -> conflict(command.commandId)
            stored.purged -> expired(command.commandId)
            else -> replayOf(stored, command.type)
        }

    private fun replayOf(
        stored: StoredOfflineCommand,
        type: String,
    ): OfflineCommandResult =
        if (stored.type != type) {
            rejected(
                stored.commandId,
                OfflineSyncCodes.COMMAND_ID_REUSED,
                "Este id de comando já foi usado em outro comando.",
            )
        } else {
            toResult(stored).copy(replayed = true)
        }

    private fun expired(id: UUID) =
        rejected(id, OfflineSyncCodes.COMMAND_EXPIRED, "O comando é antigo demais para ser aplicado.")

    /** Um horário do aparelho plausível: nem antes de 2020 nem além do adiantamento aceito. */
    private fun plausible(
        instant: Instant,
        receivedAt: Instant,
    ): Boolean =
        instant >= OfflineSyncProperties.EARLIEST_DEVICE_TIME &&
            instant <= receivedAt.plus(properties.maxDeviceClockAhead)

    private fun conflict(id: UUID) =
        rejected(id, OfflineSyncCodes.COMMAND_ID_CONFLICT, "Este id de comando já foi usado por outro usuário.")

    private fun toResult(stored: StoredOfflineCommand) =
        OfflineCommandResult(
            stored.commandId,
            stored.status,
            stored.code,
            stored.message,
            stored.warnings,
            stored.occurredAt,
        )

    private fun rejected(
        id: UUID,
        code: ErrorCode,
        message: String,
    ) = OfflineCommandResult(id, OfflineCommandStatus.REJECTED, code, message)

    private fun payloadOf(
        command: IncomingOfflineCommand,
        handler: OfflineCommandHandler<*>,
    ): Any? {
        val payload =
            try {
                jsonMapper.convertValue(command.payload, handler.payloadType)
            } catch (_: JacksonException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return payload?.takeIf { validator.validate(it).isEmpty() }
    }

    @Suppress("UNCHECKED_CAST") // o payload foi convertido para o payloadType do próprio handler
    private fun invoke(
        handler: OfflineCommandHandler<*>,
        accepted: AcceptedCommand,
        payload: Any,
    ): OfflineCommandOutcome {
        val typed = handler as OfflineCommandHandler<Any>
        return typed.handle(
            OfflineCommand(
                accepted.id,
                OfflineCommandType(accepted.type),
                payload,
                accepted.occurredAt,
                accepted.deviceOccurredAt,
            ),
        )
    }

    private fun clockWarning(skew: Duration): OfflineCommandWarning {
        val minutes = skew.abs().toMinutes()
        val direction = if (skew.isNegative) "adiantado" else "atrasado"
        return OfflineCommandWarning(
            OfflineSyncCodes.CLOCK_SKEW_EXCEEDED,
            "O relógio do dispositivo estava $direction em cerca de $minutes min; o horário foi corrigido.",
        )
    }

    private fun skewOf(
        command: IncomingOfflineCommand,
        receivedAt: Instant,
    ): Duration = Duration.between(command.deviceClock, receivedAt)

    private fun retentionLimit(): Instant = clock.instant().minus(properties.commandRetention)

    /** O instante gravado no UUIDv7 (milissegundos Unix), pelo relógio do aparelho que o gerou. */
    private fun idInstant(id: UUID): Instant = Instant.ofEpochMilli(id.mostSignificantBits ushr UUID_TIMESTAMP_SHIFT)

    /** Um comando que passou pelas recusas do protocolo e vai ao handler. */
    private data class AcceptedCommand(
        val id: UUID,
        val type: String,
        val user: UserId,
        val occurredAt: Instant,
        val deviceOccurredAt: Instant,
        val clockWarnings: List<OfflineCommandWarning>,
        val retention: CommandRetention,
    )

    companion object {
        private const val UUID_VERSION_7 = 7
        private const val UUID_TIMESTAMP_SHIFT = 16
        private const val MAX_MESSAGE = 500

        /** Confere os handlers registrados: um por tipo. */
        fun catalog(handlers: List<OfflineCommandHandler<*>>): Map<String, OfflineCommandHandler<*>> {
            val duplicated = handlers.groupBy { it.type.value }.filterValues { it.size > 1 }.keys
            require(duplicated.isEmpty()) { "Tipos de comando offline repetidos: $duplicated" }
            return handlers.associateBy { it.type.value }
        }
    }
}
