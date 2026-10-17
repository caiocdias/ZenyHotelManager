package com.zeny.hotel.guests.web

import com.zeny.hotel.guests.CompanyId
import com.zeny.hotel.guests.GuestDocumentId
import com.zeny.hotel.guests.GuestId
import com.zeny.hotel.guests.application.GuestFilter
import com.zeny.hotel.guests.application.GuestMergeService
import com.zeny.hotel.guests.application.GuestService
import com.zeny.hotel.guests.application.GuestView
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.CursorPage
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.PageParams
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
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
import java.net.URI
import java.util.UUID

internal const val GUESTS_TAG = "guests"
internal const val GUESTS_TAG_DESCRIPTION = "Perfis de hóspedes, documentos, lista de restrição e empresas."
private const val IF_MATCH_RULE = "o `If-Match` (ausente: 428; versão diferente: 412)"
private const val READ = "Exige `guests.profiles.read`."
private const val MANAGE = "Exige `guests.profiles.manage`."
private const val MERGED =
    "Num perfil mesclado: 409 `GUEST_ALREADY_MERGED` (altere o sobrevivente); num anonimizado, 409 `GUEST_ANONYMIZED`."
private const val DATA_SUBJECT_REQUEST =
    "Pedido do titular (RF-HOS-07): a trilha registra a correção com a origem `DATA_SUBJECT_REQUEST`."
private const val MASKED =
    "Os documentos vêm mascarados (só os últimos caracteres) e os dados sensíveis da FNRH nunca vêm."

/**
 * Perfis de hóspedes (RF-HOS-01, 02 e 04): cadastro com os campos da FNRH, documentos com o número cifrado e
 * mascarado nas respostas (H9), dados sensíveis da FNRH só gravados (RN-19), lista de restrição e busca por
 * nome, e-mail e documento (pelo índice cego, H8). Perfis nunca são apagados.
 */
@ApiV1Controller
@RequestMapping("/guests")
@Tag(name = GUESTS_TAG, description = GUESTS_TAG_DESCRIPTION)
@Suppress("TooManyFunctions") // uma operação por endpoint do perfil
class GuestsController(
    private val guests: GuestService,
    private val merges: GuestMergeService,
) {
    @GetMapping
    @Operation(
        operationId = "listGuests",
        summary = "Buscar perfis de hóspedes",
        description =
            "Em ordem de nome (sem acento). `name` procura cada palavra como trecho do nome ou do nome social, sem " +
                "diferenciar acento nem maiúsculas; `email` é exato. Para buscar por documento, use " +
                "`POST /api/v1/guests/search-by-document` (o número não vai na URL). Só perfis ativos (um mesclado " +
                "só se lê pelo id). $READ $MASKED",
    )
    @Suppress("LongParameterList") // os filtros da busca, cada um um parâmetro da query
    fun list(
        @Parameter(description = "Trecho do nome ou do nome social (de 2 a 200 caracteres).")
        @RequestParam(required = false)
        @Size(min = 2, max = 200)
        name: String?,
        @Parameter(description = "E-mail exato.")
        @RequestParam(required = false)
        @Size(max = 254)
        email: String?,
        @Parameter(description = "Só os da lista de restrição (`true`) ou só os fora dela (`false`).")
        @RequestParam(required = false) restricted: Boolean?,
        @Parameter(description = "Só os VIP (`true`) ou só os não VIP (`false`).")
        @RequestParam(required = false) vip: Boolean?,
        @Parameter(description = "Só os ligados a esta empresa.")
        @RequestParam(required = false) companyId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<GuestResponse> =
        guests
            .search(GuestFilter(name, email, restricted, vip, companyId?.let(::CompanyId)), page)
            .map(GuestResponse::of)

    @PostMapping("/search-by-document")
    @Operation(
        operationId = "searchGuestsByDocument",
        summary = "Buscar perfis por documento",
        description =
            "Pelo índice cego (H8): o número é normalizado como no cadastro e comparado só pelo hash, sem decifrar " +
                "os registros. Vai no corpo, nunca na URL. Até 50 perfis ativos (mais de um: duplicados). Número " +
                "inválido " +
                "para o tipo: 400 `DOCUMENT_NUMBER_INVALID`. $READ $MASKED",
    )
    fun searchByDocument(
        @Valid @RequestBody request: GuestDocumentSearchRequest,
    ): GuestListResponse = GuestListResponse(guests.searchByDocument(request.toCommand()).map(GuestResponse::of))

    @GetMapping("/duplicate-candidates")
    @Operation(
        operationId = "listGuestDuplicateCandidates",
        summary = "Listar candidatos a duplicado",
        description =
            "Pares de perfis ativos com um documento igual (pelo índice cego, sem decifrar nada) ou o mesmo e-mail " +
                "(RF-HOS-05), cada par uma vez, o de menor id primeiro, em ordem dos ids. `guestId`: só os pares " +
                "desse perfil (inexistente: 404 `GUEST_NOT_FOUND`). Documentos indexados com chaves diferentes do " +
                "anel (no meio de uma rotação) só se casam depois da reindexação. $READ $MASKED",
    )
    fun duplicateCandidates(
        @Parameter(description = "Só os pares deste perfil.")
        @RequestParam(required = false) guestId: UUID?,
        @Valid page: PageParams,
    ): CursorPage<DuplicateCandidateResponse> =
        merges.duplicateCandidates(guestId?.let(::GuestId), page).map(DuplicateCandidateResponse::of)

    @PostMapping("/{id}/merge")
    @Operation(
        operationId = "mergeGuests",
        summary = "Mesclar um perfil duplicado neste",
        description =
            "O perfil do caminho é o sobrevivente; `mergedGuestId` fica como mesclado (`MERGED`, com `mergedInto`), " +
                "nunca é apagado, e o evento `GuestsMerged` sai pelo outbox na mesma transação. Exige " +
                "`guests.profiles.merge` e $IF_MATCH_RULE do sobrevivente. O mesmo perfil: 409 " +
                "`GUEST_MERGE_SAME_PROFILE`; algum já mesclado: 409 `GUEST_ALREADY_MERGED`; documento do mesmo " +
                "tipo e " +
                "país com números diferentes: 409 `GUEST_MERGE_DOCUMENT_CONFLICT` (retire o errado antes); " +
                "inexistente: 404 `GUEST_NOT_FOUND`. Devolve o sobrevivente.",
    )
    fun merge(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: MergeGuestRequest,
    ): ResponseEntity<GuestResponse> =
        ok(
            merges.merge(
                GuestId(id),
                GuestId(checkNotNull(request.mergedGuestId)),
                ETags.expectedVersion(ifMatch),
                request.takeFromMerged,
            ),
        )

    @PostMapping
    @Operation(
        operationId = "createGuest",
        summary = "Cadastrar um perfil de hóspede",
        description =
            "$MANAGE O endereço completo é só de quem reside no Brasil (400 " +
                "`ADDRESS_REQUIRES_BRAZIL_RESIDENCE`). CPF com dígito verificador errado ou número fora do " +
                "formato do tipo: 400 `DOCUMENT_NUMBER_INVALID`; país emissor incompatível com o tipo: 400 " +
                "`DOCUMENT_COUNTRY_INVALID`. Restrições alimentares sem o consentimento `DIETARY_RESTRICTIONS` " +
                "(que pode vir em `consents`): 422 `DIETARY_RESTRICTIONS_REQUIRE_CONSENT`.",
    )
    @ApiResponse(responseCode = "201", description = "Perfil cadastrado.")
    fun create(
        @Valid @RequestBody request: CreateGuestRequest,
    ): ResponseEntity<GuestResponse> {
        val view =
            guests.create(
                checkNotNull(request.profile).toCommand(),
                request.documents.map(GuestDocumentRequest::toCommand),
                request.sensitiveData?.toCommand(),
                request.consents.map(ConsentRequest::toCommand),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/guests/${view.id}"))
            .eTag(ETags.of(view.version))
            .body(GuestResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getGuest",
        summary = "Consultar um perfil de hóspede",
        description =
            "$READ $MASKED Um perfil mesclado continua respondendo, com `status` `MERGED` e o sobrevivente em " +
                "`mergedInto`. Inexistente: 404 `GUEST_NOT_FOUND`.",
    )
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<GuestResponse> = ok(guests.guest(GuestId(id)))

    @PutMapping("/{id}")
    @Operation(
        operationId = "updateGuest",
        summary = "Corrigir um perfil de hóspede",
        description =
            "Troca os dados do perfil (não os documentos, os dados sensíveis nem a restrição, que têm ações " +
                "próprias). $MANAGE e $IF_MATCH_RULE. $MERGED",
    )
    fun update(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Parameter(description = DATA_SUBJECT_REQUEST)
        @RequestParam(defaultValue = "false") dataSubjectRequest: Boolean,
        @Valid @RequestBody request: GuestProfileRequest,
    ): ResponseEntity<GuestResponse> =
        ok(guests.update(GuestId(id), ETags.expectedVersion(ifMatch), request.toCommand(), dataSubjectRequest))

    @PostMapping("/{id}/documents")
    @Operation(
        operationId = "addGuestDocument",
        summary = "Acrescentar um documento",
        description =
            "$MANAGE Um documento de cada tipo e país por perfil (repetido: 409 `DOCUMENT_ALREADY_RECORDED`; " +
                "para corrigir, retire o anterior). Devolve o perfil. $MERGED",
    )
    fun addDocument(
        @PathVariable id: UUID,
        @Parameter(description = DATA_SUBJECT_REQUEST)
        @RequestParam(defaultValue = "false") dataSubjectRequest: Boolean,
        @Valid @RequestBody request: GuestDocumentRequest,
    ): ResponseEntity<GuestResponse> = ok(guests.addDocument(GuestId(id), request.toCommand(), dataSubjectRequest))

    @PostMapping("/{id}/documents/{documentId}/remove")
    @Operation(
        operationId = "removeGuestDocument",
        summary = "Retirar um documento",
        description =
            "$MANAGE De outro perfil ou inexistente: 404 `GUEST_DOCUMENT_NOT_FOUND`. Devolve o perfil. $MERGED",
    )
    fun removeDocument(
        @PathVariable id: UUID,
        @PathVariable documentId: UUID,
        @Parameter(description = DATA_SUBJECT_REQUEST)
        @RequestParam(defaultValue = "false") dataSubjectRequest: Boolean,
    ): ResponseEntity<GuestResponse> =
        ok(guests.removeDocument(GuestId(id), GuestDocumentId(documentId), dataSubjectRequest))

    @PostMapping("/{id}/documents/{documentId}/reveal")
    @Operation(
        operationId = "revealGuestDocument",
        summary = "Revelar o número de um documento",
        description =
            "O número inteiro (H9). Exige `guests.documents.reveal`; cada acesso fica na trilha de auditoria. A " +
                "resposta não vai para cache (`Cache-Control: no-store`).",
    )
    fun revealDocument(
        @PathVariable id: UUID,
        @PathVariable documentId: UUID,
    ): ResponseEntity<RevealedDocumentResponse> =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(RevealedDocumentResponse.of(guests.revealDocument(GuestId(id), GuestDocumentId(documentId))))

    @PutMapping("/{id}/sensitive-data")
    @Operation(
        operationId = "recordGuestSensitiveData",
        summary = "Gravar os dados sensíveis da FNRH",
        description =
            "Raça/cor e deficiência (RN-19): ficam cifrados e nunca voltam pela API (só `hasSensitiveData`); os " +
                "dois nulos apagam. Define o valor, sem `If-Match`. $MANAGE $MERGED",
    )
    fun recordSensitiveData(
        @PathVariable id: UUID,
        @Parameter(description = DATA_SUBJECT_REQUEST)
        @RequestParam(defaultValue = "false") dataSubjectRequest: Boolean,
        @Valid @RequestBody request: GuestSensitiveDataRequest,
    ): ResponseEntity<GuestResponse> =
        ok(guests.recordSensitiveData(GuestId(id), request.toCommand(), dataSubjectRequest))

    @PostMapping("/{id}/restrict")
    @Operation(
        operationId = "restrictGuest",
        summary = "Incluir na lista de restrição",
        description =
            "Com o motivo, que gera alerta na reserva e no check-in (RF-HOS-04); de novo, troca o motivo. Exige " +
                "`guests.restrictions.manage`. $MERGED",
    )
    fun restrict(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RestrictGuestRequest,
    ): ResponseEntity<GuestResponse> = ok(guests.restrict(GuestId(id), checkNotNull(request.reason)))

    @PostMapping("/{id}/lift-restriction")
    @Operation(
        operationId = "liftGuestRestriction",
        summary = "Retirar da lista de restrição",
        description = "Exige `guests.restrictions.manage`. Fora da lista, não muda nada. $MERGED",
    )
    fun liftRestriction(
        @PathVariable id: UUID,
    ): ResponseEntity<GuestResponse> = ok(guests.liftRestriction(GuestId(id)))

    private fun ok(view: GuestView) = ResponseEntity.ok().eTag(ETags.of(view.version)).body(GuestResponse.of(view))
}
