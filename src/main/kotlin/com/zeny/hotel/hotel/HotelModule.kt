package com.zeny.hotel.hotel

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `hotel` (ARCHITECTURE §3.1, SPEC §5.2): os dados do hotel único, as
 * políticas, as taxas, os parâmetros operacionais, o fuso e a **data operacional** (RN-01). Depende
 * de `shared` e de `files` (só da [FilesApi][com.zeny.hotel.files.FilesApi]: o logotipo é um anexo
 * da finalidade [HotelFilePurposes.LOGO], H8).
 *
 * A raiz do pacote é a API pública: [HotelApi] (leituras para os outros módulos e o avanço da data
 * operacional, que só o fechamento do dia chama), o evento [BusinessDateAdvanced], os tipos de
 * valor da configuração, os códigos de erro ([HotelErrorCodes]), as permissões ([HotelPermissions])
 * e as finalidades de anexo ([HotelFilePurposes]).
 */
@PackageInfo
@ApplicationModule(displayName = "Hotel and business date", allowedDependencies = ["shared", "files"])
class HotelModule
