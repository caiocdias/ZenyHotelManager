package apiexample

import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.BusinessRuleException
import com.zeny.hotel.shared.ConflictException
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.ErrorCode
import com.zeny.hotel.shared.Money
import com.zeny.hotel.shared.NotFoundException
import com.zeny.hotel.shared.PageCursor
import com.zeny.hotel.shared.PageParams
import com.zeny.hotel.shared.Percent
import com.zeny.hotel.shared.PermissionDeniedException
import com.zeny.hotel.shared.UuidV7
import com.zeny.hotel.shared.requireCurrentVersion
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Controller de exemplo, só de teste, que exercita as convenções da API (ARCHITECTURE §7):
 * prefixo `/api/v1`, erros RFC 9457, paginação por cursor, `ETag`/`If-Match` e serialização.
 * Fica fora do pacote base de propósito: não entra no contrato de produção nem na varredura
 * de componentes; os testes o importam com [ExampleApiConfiguration].
 */
@ApiV1Controller
@RequestMapping("/examples")
@Tag(name = "examples")
class ExampleItemsController(
    private val store: ExampleItemStore,
) {
    @GetMapping
    @Operation(operationId = "listExampleItems")
    fun list(
        @Valid page: PageParams,
    ): CursorPage<ExampleItemResponse> {
        val after = page.decodedCursor()?.uuid(0)
        val candidates = if (after == null) store.items.values else store.items.tailMap(after, false).values
        return CursorPage
            .fromRows(candidates.take(page.limit + 1), page.limit) { PageCursor.of(it.id.toString()) }
            .map(ExampleItem::toResponse)
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getExampleItem")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<ExampleItemResponse> {
        val item = store.find(id)
        return ResponseEntity.ok().eTag(ETags.of(item.version)).body(item.toResponse())
    }

    @PostMapping
    @Operation(operationId = "createExampleItem")
    fun create(
        @Valid @RequestBody request: CreateExampleItemRequest,
    ): ResponseEntity<ExampleItemResponse> {
        val item =
            ExampleItem(
                id = UuidV7.generate(),
                name = request.name,
                price = request.price,
                dueDate = request.dueDate,
                createdAt = Instant.parse("2026-03-10T01:30:00.123Z"),
            )
        store.save(item)
        return ResponseEntity
            .created(URI.create("/api/v1/examples/${item.id}"))
            .eTag(ETags.of(item.version))
            .body(item.toResponse())
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateExampleItem")
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: UpdateExampleItemRequest,
    ): ResponseEntity<ExampleItemResponse> {
        val expectedVersion = ETags.expectedVersion(ifMatch)
        val current = store.find(id)
        requireCurrentVersion(expectedVersion, current.version)
        val updated = current.copy(name = request.name, price = request.price, version = current.version + 1)
        store.save(updated)
        return ResponseEntity.ok().eTag(ETags.of(updated.version)).body(updated.toResponse())
    }

    @PostMapping("/{id}/archive")
    @Operation(operationId = "archiveExampleItem")
    fun archive(
        @PathVariable id: UUID,
    ): ExampleItemResponse {
        val current = store.find(id)
        if (current.archived) {
            throw ConflictException(ErrorCode("EXAMPLE_ALREADY_ARCHIVED"), "O item já está arquivado.")
        }
        return current.copy(archived = true, version = current.version + 1).also(store::save).toResponse()
    }

    @PostMapping("/{id}/discount")
    @Operation(operationId = "discountExampleItem")
    fun discount(
        @PathVariable id: UUID,
        @RequestBody request: DiscountRequest,
    ): ExampleItemResponse {
        val current = store.find(id)
        if (request.percent > MAX_DISCOUNT) {
            throw BusinessRuleException(ErrorCode("LIMIT_EXCEEDED"), "O desconto passa do limite de 10%.")
        }
        return current.copy(price = current.price - current.price * request.percent).toResponse()
    }

    /** Restrição direto no parâmetro: o Spring valida pelo método (`HandlerMethodValidationException`). */
    @GetMapping("/search")
    @Operation(operationId = "searchExampleItems")
    fun search(
        @RequestParam @Size(min = 3) q: String,
    ): List<ExampleItemResponse> =
        store.items.values
            .filter { q in it.name }
            .map(ExampleItem::toResponse)

    @GetMapping("/approval")
    @Operation(operationId = "approvalExample")
    fun approval(): Nothing =
        throw PermissionDeniedException(ErrorCode("APPROVAL_REQUIRED"), "Precisa da aprovação de um superior.")

    @GetMapping("/boom")
    @Operation(operationId = "boomExample")
    fun boom(): Nothing = error("falha inesperada com dado sensível: senha=hunter2")

    private companion object {
        val MAX_DISCOUNT: Percent = Percent.of(BigDecimal.TEN)
    }
}

data class CreateExampleItemRequest(
    @field:NotBlank
    @field:Size(max = 40)
    val name: String,
    val price: Money,
    val dueDate: LocalDate,
)

data class UpdateExampleItemRequest(
    @field:NotBlank
    val name: String,
    val price: Money,
)

data class DiscountRequest(
    val percent: Percent,
)

data class ExampleItemResponse(
    val id: UUID,
    val name: String,
    val price: Money,
    val dueDate: LocalDate,
    val createdAt: Instant,
    val archived: Boolean,
)

data class ExampleItem(
    val id: UUID,
    val name: String,
    val price: Money,
    val dueDate: LocalDate,
    val createdAt: Instant,
    val archived: Boolean = false,
    val version: Long = 0,
) {
    fun toResponse() = ExampleItemResponse(id, name, price, dueDate, createdAt, archived)
}

/** Armazém em memória do exemplo, em ordem de id (UUIDv7, logo em ordem de criação). */
class ExampleItemStore {
    val items = ConcurrentSkipListMap<UUID, ExampleItem>()

    fun find(id: UUID): ExampleItem =
        items[id] ?: throw NotFoundException(ErrorCode("EXAMPLE_NOT_FOUND"), "Item de exemplo não encontrado.")

    fun save(item: ExampleItem) {
        items[item.id] = item
    }
}

@Configuration(proxyBeanMethods = false)
class ExampleApiConfiguration {
    @Bean
    fun exampleItemStore() = ExampleItemStore()

    @Bean
    fun exampleItemsController(store: ExampleItemStore) = ExampleItemsController(store)
}
