package com.zeny.hotel.iam.infrastructure

import com.zeny.hotel.iam.application.IamProperties
import com.zeny.hotel.iam.application.InitialAdminService
import com.zeny.hotel.iam.application.RotatedRefreshTokens
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Cria o administrador inicial no boot, se não houver usuário (H2). Falha impede a partida. */
@Component
class InitialAdminRunner(
    private val initialAdmin: InitialAdminService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        initialAdmin.ensureInitialAdmin()
    }
}

/**
 * Expurga os refresh tokens trocados há mais que a validade do refresh token (30 dias), sob
 * trava do ShedLock (ARCHITECTURE §13). Um token tão antigo já estaria vencido: reapresentá-lo
 * depois do expurgo só dá `REFRESH_TOKEN_INVALID`, sem revogar a sessão.
 */
@Component
class RotatedRefreshTokenPurgeJob(
    private val rotated: RotatedRefreshTokens,
    private val properties: IamProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${zeny.iam.refresh-token.purge.interval}",
        initialDelayString = "\${zeny.iam.refresh-token.purge.initial-delay}",
    )
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    @Transactional
    fun purgeRotatedTokens() {
        val purged = rotated.deleteRotatedBefore(clock.instant().minus(properties.refreshToken.ttl))
        if (purged >
            0
        ) {
            log.info("Expurgados {} refresh tokens trocados há mais de {}", purged, properties.refreshToken.ttl)
        }
    }

    companion object {
        const val LOCK_NAME = "iam-rotated-refresh-tokens-purge"
    }
}
