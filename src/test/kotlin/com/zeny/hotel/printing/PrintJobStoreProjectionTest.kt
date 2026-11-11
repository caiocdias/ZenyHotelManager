package com.zeny.hotel.printing

import com.zeny.hotel.iam.IamTestConfiguration
import com.zeny.hotel.iam.IamTestSupport
import com.zeny.hotel.printing.application.NewPrinter
import com.zeny.hotel.printing.application.PrintAgentsService
import com.zeny.hotel.printing.application.PrintJobStore
import com.zeny.hotel.printing.application.PrintJobsService
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.UserId
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.testsupport.IntegrationTest
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * A listagem e as consultas de candidatos da manutenção não carregam o `content` cifrado (revisão final da M5 E10, I1):
 * nenhuma entidade `PrintJob` é carregada, só colunas.
 */
@IntegrationTest
@Import(IamTestConfiguration::class)
@Suppress("LongParameterList")
class PrintJobStoreProjectionTest(
    @Autowired private val jobs: PrintJobsService,
    @Autowired private val agents: PrintAgentsService,
    @Autowired private val store: PrintJobStore,
    @Autowired private val iam: IamTestSupport,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    private fun suffix() = UuidV7.generate().toString().takeLast(12)

    private fun <T> asAdmin(block: () -> T): T {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(UserId(iam.adminId()).toString())
                .claim("sid", UuidV7.generate().toString())
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun loadedEntities(block: () -> Unit): Long {
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        val before = statistics.getEntityStatistics(PRINT_JOB).loadCount
        tx.executeWithoutResult { _ -> block() }
        return statistics.getEntityStatistics(PRINT_JOB).loadCount - before
    }

    @Test
    fun `the listing and every maintenance candidate query read columns without loading the content`() {
        val agent = asAdmin { agents.createAgent("Agente ${suffix()}").view.id }
        val printer =
            asAdmin {
                agents.createPrinter(NewPrinter("Imp ${suffix()}", PrinterKind.A4_PDF, "addr-${suffix()}", agent)).id
            }
        val sent = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", "%PDF-1.7 ${suffix()}".toByteArray()))
        val pending = jobs.enqueue(NewPrintJob(printer, "frontdesk.card", "%PDF-1.7 ${suffix()}".toByteArray()))
        // Instantes atuais, não antigos: o banco é compartilhado e outros testes contam com cortes de 2002 e 2003.
        tx.executeWithoutResult { _ -> store.lockById(sent)!!.claim(agent, Instant.now()) }
        val later = Instant.now().plusSeconds(60)
        val found = mutableListOf<UUID>()

        loadedEntities {
            found += store.page(null, printer, PageParams(limit = 10)).items.map { it.id }
            found += store.expiredSent(later, MANY).map { it.id }
            found += store.stalePending(later, MANY).map { it.id }
            store.ofInactivePrinters(MANY)
            store.ofDeactivatedAgents(MANY)
            store.purgeable(Instant.now(), MANY)
        } shouldBe 0L
        found shouldContain sent
        found shouldContain pending
    }

    private companion object {
        const val PRINT_JOB = "com.zeny.hotel.printing.domain.PrintJob"
        const val MANY = 1000
    }
}
