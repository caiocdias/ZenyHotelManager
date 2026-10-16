package realtimeexample

import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.RealtimeMessage
import com.zeny.hotel.shared.RealtimePublisher
import com.zeny.hotel.shared.RealtimeTopic
import com.zeny.hotel.shared.UserId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Um módulo de exemplo, só de teste, que registra um tópico de tempo real com escopo `OWN` (ARCHITECTURE
 * §10): o assinante com `OWN` só recebe as notas de que é dono; com `ALL`, todas.
 */
object RealtimeExamplePermissions : ModulePermissions {
    override val module = "realtimeexample"

    val NOTES_READ = PermissionCode("realtimeexample.notes.read")

    override val permissions =
        listOf(PermissionDefinition(NOTES_READ, "Acompanhar notas de exemplo em tempo real", setOf(OWN, ALL)))
}

object RealtimeExampleTopics {
    val NOTES = RealtimeTopic("example-notes", RealtimeExamplePermissions.NOTES_READ)

    const val NOTE = "realtimeexample.note"
}

/** Publica notas de exemplo no tópico, cada uma na sua transação (que pode ser desfeita). */
class RealtimeExamplePublisher(
    private val realtime: RealtimePublisher,
    private val transactions: TransactionTemplate,
) {
    fun publish(
        id: UUID = UUID.randomUUID(),
        version: Long = 0,
        owners: Set<UserId> = emptySet(),
        rollback: Boolean = false,
    ): UUID {
        transactions.executeWithoutResult { status ->
            realtime.publish(
                RealtimeExampleTopics.NOTES,
                RealtimeMessage(RealtimeExampleTopics.NOTE, id, version, owners),
            )
            if (rollback) status.setRollbackOnly()
        }
        return id
    }
}

@TestConfiguration(proxyBeanMethods = false)
class RealtimeExampleConfiguration {
    @Bean
    fun realtimeExamplePermissions(): ModulePermissions = RealtimeExamplePermissions

    @Bean
    fun realtimeExampleTopic(): RealtimeTopic = RealtimeExampleTopics.NOTES

    @Bean
    fun realtimeExamplePublisher(
        realtime: RealtimePublisher,
        transactions: TransactionTemplate,
    ) = RealtimeExamplePublisher(realtime, transactions)
}
