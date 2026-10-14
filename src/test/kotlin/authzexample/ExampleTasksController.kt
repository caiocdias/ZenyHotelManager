package authzexample

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.CurrentActor
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.LimitDefinition
import com.zeny.hotel.shared.LimitUnit
import com.zeny.hotel.shared.LimitValue
import com.zeny.hotel.shared.ModulePermissions
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.OwnedResource
import com.zeny.hotel.shared.PermissionCode
import com.zeny.hotel.shared.PermissionDefinition
import com.zeny.hotel.shared.PermissionScope.ALL
import com.zeny.hotel.shared.PermissionScope.OWN
import com.zeny.hotel.shared.UserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * Permissões de um módulo de exemplo, só de teste: como um módulo declara o seu catálogo
 * (ARCHITECTURE §8.2). `examples.tasks.*` aceitam `OWN` e `ALL`; o desconto tem um limite.
 */
object ExamplePermissions : ModulePermissions {
    override val module = "examples"

    val TASKS_READ = PermissionCode("examples.tasks.read")
    val TASKS_COMPLETE = PermissionCode("examples.tasks.complete")
    val DISCOUNTS_APPLY = PermissionCode("examples.discounts.apply")
    const val MAX_PERCENT = "maxPercent"

    /** Escopo e limite juntos: a união de `ALL` com teto 0 e `OWN` com teto 100 é `ALL` com teto 100. */
    val REFUNDS_ISSUE = PermissionCode("examples.refunds.issue")
    const val MAX_AMOUNT = "maxAmount"

    override val permissions =
        listOf(
            PermissionDefinition(TASKS_READ, "Consultar tarefas de exemplo", setOf(OWN, ALL)),
            PermissionDefinition(TASKS_COMPLETE, "Concluir tarefas de exemplo", setOf(OWN, ALL)),
            PermissionDefinition(
                DISCOUNTS_APPLY,
                "Aplicar desconto de exemplo",
                setOf(ALL),
                sensitive = true,
                limits = listOf(LimitDefinition(MAX_PERCENT, LimitUnit.PERCENT, "Desconto máximo em %")),
            ),
            PermissionDefinition(
                REFUNDS_ISSUE,
                "Estornar de exemplo",
                setOf(OWN, ALL),
                sensitive = true,
                limits = listOf(LimitDefinition(MAX_AMOUNT, LimitUnit.MONEY, "Valor máximo do estorno")),
            ),
        )
}

data class ExampleTaskResponse(
    val id: UUID,
    val title: String,
    val assigneeId: UUID?,
    val createdBy: UUID,
    val completed: Boolean,
)

data class ExampleLimitResponse(
    val unlimited: Boolean,
    val max: String?,
)

/**
 * Casos de uso de exemplo, só de teste, que usam a porta [Authorization] como um módulo de negócio
 * usaria: a consulta aplica o filtro de escopo na própria SQL (`OWN` → atribuídas ao usuário ou
 * criadas por ele) e o comando checa a posse do registro.
 */
open class ExampleTaskService(
    private val authorization: Authorization,
    private val currentActor: CurrentActor,
    private val jdbc: JdbcClient,
) {
    @Transactional(readOnly = true)
    open fun list(): List<ExampleTaskResponse> {
        val owner = authorization.scopeFilter(ExamplePermissions.TASKS_READ).ownerId
        return jdbc
            .sql(
                """
                SELECT id, title, assignee_id, created_by, completed FROM test_support.example_task
                WHERE CAST(:owner AS uuid) IS NULL
                   OR assignee_id = CAST(:owner AS uuid) OR created_by = CAST(:owner AS uuid)
                ORDER BY id
                """.trimIndent(),
            ).param("owner", owner)
            .query { rs, _ ->
                ExampleTaskResponse(
                    id = rs.getObject("id", UUID::class.java),
                    title = rs.getString("title"),
                    assigneeId = rs.getObject("assignee_id", UUID::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    completed = rs.getBoolean("completed"),
                )
            }.list()
    }

    @Transactional
    open fun complete(id: UUID) {
        currentActor.current()
        val owners =
            jdbc
                .sql("SELECT assignee_id, created_by FROM test_support.example_task WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query { rs, _ ->
                    OwnedResource.of(
                        rs.getObject("assignee_id", UUID::class.java)?.let(::UserId),
                        UserId(rs.getObject("created_by", UUID::class.java)),
                    )
                }.optional()
                .orElseThrow { NotFoundException(ErrorCode("EXAMPLE_TASK_NOT_FOUND"), "Tarefa não encontrada.") }
        authorization.require(ExamplePermissions.TASKS_COMPLETE, owners)
        jdbc.sql("UPDATE test_support.example_task SET completed = true WHERE id = :id").param("id", id).update()
    }

    @Transactional(readOnly = true)
    open fun discountLimit(): LimitValue =
        authorization.limit(ExamplePermissions.DISCOUNTS_APPLY, ExamplePermissions.MAX_PERCENT)
}

@ApiV1Controller
@RequestMapping("/authz-examples")
@Tag(name = "examples")
class ExampleTasksController(
    private val service: ExampleTaskService,
) {
    @GetMapping("/tasks")
    @Operation(operationId = "listAuthzExampleTasks")
    fun list(): List<ExampleTaskResponse> = service.list()

    @PostMapping("/tasks/{id}/complete")
    @Operation(operationId = "completeAuthzExampleTask")
    fun complete(
        @PathVariable id: UUID,
    ) = service.complete(id)

    @GetMapping("/discount-limit")
    @Operation(operationId = "getAuthzExampleDiscountLimit")
    fun discountLimit(): ExampleLimitResponse =
        when (val limit = service.discountLimit()) {
            LimitValue.Unlimited -> ExampleLimitResponse(unlimited = true, max = null)
            is LimitValue.Max -> ExampleLimitResponse(unlimited = false, max = limit.value.toPlainString())
        }
}

/** Liga o módulo de exemplo ao contexto: as permissões entram no catálogo no boot. */
@TestConfiguration(proxyBeanMethods = false)
class AuthzExampleConfiguration {
    @Bean
    fun examplePermissions(): ModulePermissions = ExamplePermissions

    @Bean
    fun exampleTaskService(
        authorization: Authorization,
        currentActor: CurrentActor,
        jdbc: JdbcClient,
    ) = ExampleTaskService(authorization, currentActor, jdbc)

    @Bean
    fun exampleTasksController(service: ExampleTaskService) = ExampleTasksController(service)
}
