package com.zeny.hotel.reporting

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `reporting` (ARCHITECTURE §3.1, camada Gestão; SPEC §5.18): os relatórios operacionais, o painel
 * do dia e as pendências do fechamento (M6 E09). Não importa classe de outro módulo de negócio: consulta só as views
 * `report_*` que os módulos publicam (ARCHITECTURE §11), por um adaptador `JdbcClient` atrás das portas de
 * `application`. Depende só de `shared` (o kernel e a porta de autorização), de `hotel` (a data operacional e o fuso,
 * só pela `HotelApi`) e, desde a E23, de `printing` (o PDF da data, só pela `PrintingApi` e os tipos dela) e de `files`
 * (o anexo sensível, só pela `FilesApi` e os tipos dela).
 *
 * A raiz do pacote é a API pública: as permissões ([ReportingPermissions]), os códigos de erro
 * ([ReportingErrorCodes]), a finalidade de anexo ([ReportingFilePurposes]) e a [ReportingApi] (o snapshot diário das
 * estatísticas e o arquivo por data, M6 E23).
 */
@PackageInfo
@ApplicationModule(displayName = "Reporting", allowedDependencies = ["shared", "hotel", "printing", "files"])
class ReportingModule
