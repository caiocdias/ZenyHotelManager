package com.zeny.hotel.shared.infrastructure.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * Descarta os eventos com exceção do logger do `JdbcTemplateStorageAccessor` do ShedLock (ARCHITECTURE §13 e §16): ele
 * loga a `DataAccessException` da trava com a mensagem do JDBC, que pode trazer valores do banco. Os eventos sem
 * exceção, como o "Unlock failed after 10 attempts", passam. A exceção pode vir como `Throwable` do evento
 * (`error(msg, t)`) ou entre os parâmetros (`error("... {}", t)`): num filtro de turbo, neste caso ela chega em
 * [params], com o `Throwable` nulo.
 *
 * É instalado a cada inicialização do logging por [ShedLockLogFilterInstaller].
 */
class ShedLockLogFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger,
        level: Level?,
        format: String?,
        params: Array<Any?>?,
        t: Throwable?,
    ): FilterReply =
        if (logger.name == SHEDLOCK_STORAGE_LOGGER && (t != null || params.orEmpty().any { it is Throwable })) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }

    companion object {
        const val SHEDLOCK_STORAGE_LOGGER =
            "net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateStorageAccessor"
    }
}
