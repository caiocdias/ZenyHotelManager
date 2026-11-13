package endofdayexample

import com.zeny.hotel.endofday.EndOfDayPermissions
import com.zeny.hotel.shared.APPROVAL_TOKEN_HEADER
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.Authorization
import com.zeny.hotel.shared.SensitiveAction
import com.zeny.hotel.shared.SensitiveAuthorization
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping

/** Como a ação foi autorizada: pela permissão do próprio usuário ou por uma aprovação. */
data class EndOfDayExampleResponse(
    val byApproval: Boolean,
)

/** Caso de uso de exemplo, só de teste: usa `endofday.runs.execute` como o fechamento manual usará (sem limite). */
open class EndOfDayExampleService(
    private val authorization: Authorization,
) {
    @Audited("endofday.example.execute")
    @Transactional
    open fun execute(approvalToken: String?): EndOfDayExampleResponse =
        when (
            authorization.requireOrApproval(
                SensitiveAction(
                    action = "endofday.example.execute",
                    permission = EndOfDayPermissions.RUNS_EXECUTE,
                    resourceRef = "endofday.example:manual",
                ),
                approvalToken,
            )
        ) {
            is SensitiveAuthorization.ByPermission -> EndOfDayExampleResponse(false)
            is SensitiveAuthorization.ByApproval -> EndOfDayExampleResponse(true)
        }
}

@ApiV1Controller
@RequestMapping("/endofday-examples")
@Tag(name = "examples")
class EndOfDayExampleController(
    private val service: EndOfDayExampleService,
) {
    @PostMapping("/execute")
    @Operation(operationId = "executeEndOfDayExample")
    fun execute(
        @RequestHeader(APPROVAL_TOKEN_HEADER, required = false) approvalToken: String?,
    ): EndOfDayExampleResponse = service.execute(approvalToken)
}

/** Liga o caso de uso de exemplo ao contexto de teste. */
@TestConfiguration(proxyBeanMethods = false)
class EndOfDayExampleConfiguration {
    @Bean
    fun endOfDayExampleService(authorization: Authorization) = EndOfDayExampleService(authorization)

    @Bean
    fun endOfDayExampleController(service: EndOfDayExampleService) = EndOfDayExampleController(service)
}
