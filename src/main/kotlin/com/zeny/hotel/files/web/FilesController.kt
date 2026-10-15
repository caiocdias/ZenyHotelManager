package com.zeny.hotel.files.web

import com.zeny.hotel.files.FileId
import com.zeny.hotel.files.application.DownloadUrl
import com.zeny.hotel.files.application.FilesService
import com.zeny.hotel.files.application.StoredFileView
import com.zeny.hotel.files.application.UploadCommand
import com.zeny.hotel.shared.API_V1
import com.zeny.hotel.shared.ApiV1Controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Anexos (ARCHITECTURE §8.3 e §14): upload por finalidade e download só por URL assinada de curta
 * duração. O upload é multipart e não é idempotente (`Idempotency-Key` não se aplica a multipart):
 * repetir gera outro anexo, que fica sem dono.
 */
@ApiV1Controller
@RequestMapping("/files")
@Tag(name = "files", description = "Anexos: upload por finalidade e download por URL assinada.")
class FilesController(
    private val files: FilesService,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        operationId = "uploadFile",
        summary = "Enviar um anexo",
        description =
            "Multipart com `purpose` (a finalidade, como `hotel.logo`) e `file`. Exige a permissão de upload da " +
                "finalidade (403 `PERMISSION_DENIED`). O tipo é detectado pelo conteúdo, não pelo `Content-Type` nem " +
                "pela extensão. Recusas (400, com o campo em `errors[]`): `UNKNOWN_FILE_PURPOSE`, `FILE_EMPTY`, " +
                "`FILE_TOO_LARGE` (acima do máximo da finalidade), `FILE_TYPE_NOT_ACCEPTED` (conteúdo de tipo não " +
                "aceito) e `FILE_EXTENSION_MISMATCH` (extensão diferente do conteúdo); acima do máximo do servidor, " +
                "413 `PAYLOAD_TOO_LARGE`. Anexo sensível é cifrado antes de ir ao armazenamento. Não aceita " +
                "`Idempotency-Key`: repetir o envio cria outro anexo.",
    )
    @ApiResponse(responseCode = "201", description = "Anexo gravado.")
    fun upload(
        @Parameter(description = "Finalidade do anexo (`<módulo>.<nome>`), como `hotel.logo`.")
        @RequestPart("purpose")
        purpose: String,
        @Parameter(description = "O arquivo; o tipo é detectado pelo conteúdo.")
        @RequestPart("file")
        file: MultipartFile,
    ): ResponseEntity<FileResponse> {
        val view = files.upload(UploadCommand(purpose, file.originalFilename, file.bytes))
        return ResponseEntity.created(URI.create("$API_V1/files/${view.id.value}")).body(FileResponse.of(view))
    }

    @GetMapping("/{id}")
    @Operation(
        operationId = "getFile",
        summary = "Consultar os metadados de um anexo",
        description =
            "Exige a permissão de download da finalidade (com escopo `OWN`, só de quem enviou). Inexistente: 404 " +
                "`FILE_NOT_FOUND`.",
    )
    fun metadata(
        @PathVariable id: UUID,
    ): FileResponse = FileResponse.of(files.metadata(FileId(id)))

    @GetMapping("/{id}/download-url")
    @Operation(
        operationId = "getFileDownloadUrl",
        summary = "Gerar a URL de download de um anexo",
        description =
            "URL assinada de curta duração (`zeny.files.download-url-ttl`, padrão 5 min), sem precisar do access " +
                "token. Exige a permissão de download da finalidade (com escopo `OWN`, só de quem enviou). Anexo " +
                "comum: URL direta ao armazenamento. Anexo sensível: URL do próprio servidor, que decifra o " +
                "conteúdo; a emissão e cada download vão para a trilha de auditoria.",
    )
    fun downloadUrl(
        @PathVariable id: UUID,
    ): DownloadUrlResponse =
        when (val url = files.downloadUrl(FileId(id))) {
            is DownloadUrl.Direct -> DownloadUrlResponse(url.url.toString(), url.expiresAt)
            is DownloadUrl.Server -> DownloadUrlResponse(serverUrl(url).toString(), url.expiresAt)
        }

    @GetMapping("/{id}/content")
    @SecurityRequirements
    @Operation(
        operationId = "downloadFileContent",
        summary = "Baixar um anexo sensível",
        description =
            "Público: quem autoriza é o `token` da URL gerada em `GET /files/{id}/download-url`, válido até " +
                "`expiresAt`. Devolve o conteúdo original, decifrado, e grava o download na trilha em nome de quem " +
                "gerou a URL. Token adulterado, de outro anexo ou ilegível: 403 `DOWNLOAD_URL_INVALID`; vencido: 403 " +
                "`DOWNLOAD_URL_EXPIRED`.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "O conteúdo do anexo, com o tipo detectado no upload.",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        schema = Schema(type = "string", format = "binary"),
                    ),
                ],
            ),
        ],
    )
    fun content(
        @PathVariable id: UUID,
        @Parameter(description = "Assinatura da URL de download.") @RequestParam("token") token: String,
    ): ResponseEntity<ByteArray> {
        val file = files.download(FileId(id), token)
        return ResponseEntity
            .status(HttpStatus.OK)
            .contentType(MediaType.parseMediaType(file.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(file.downloadName, Charsets.UTF_8)
                    .build()
                    .toString(),
            ).cacheControl(CacheControl.noStore())
            .header(CONTENT_SECURITY_POLICY, "default-src 'none'; sandbox")
            .body(file.content)
    }

    private fun serverUrl(url: DownloadUrl.Server): URI =
        ServletUriComponentsBuilder
            .fromCurrentContextPath()
            .path("$API_V1/files/{id}/content")
            .queryParam("token", "{token}")
            .encode()
            .buildAndExpand(url.fileId.value, url.token)
            .toUri()

    private companion object {
        const val CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    }
}

@Schema(description = "Metadados de um anexo.")
data class FileResponse(
    val id: UUID,
    @field:Schema(description = "Finalidade.", example = "hotel.logo")
    val purpose: String,
    @field:Schema(description = "Tipo detectado no conteúdo.", example = "image/png")
    val contentType: String,
    @field:Schema(description = "Tamanho do conteúdo original, em bytes.")
    val sizeBytes: Long,
    @field:Schema(description = "Anexo sensível: cifrado no armazenamento e com cada acesso auditado.")
    val sensitive: Boolean,
    @field:Schema(description = "Id do registro dono, depois de vinculado pelo módulo da finalidade.")
    val ownerEntityId: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(view: StoredFileView) =
            FileResponse(
                view.id.value,
                view.purpose.value,
                view.contentType,
                view.sizeBytes,
                view.sensitive,
                view.ownerEntityId,
                view.createdAt,
            )
    }
}

@Schema(description = "URL assinada de download, válida até `expiresAt`.")
data class DownloadUrlResponse(
    @field:Schema(description = "URL que baixa o anexo sem o access token, até vencer.")
    val url: String,
    val expiresAt: Instant,
)
