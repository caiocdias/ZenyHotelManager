package com.zeny.hotel.hotel

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `hotel` (ARCHITECTURE §3.1, SPEC §5.2): os dados do hotel único, as
 * políticas, as taxas, os parâmetros operacionais, o fuso e a **data operacional** (RN-01). Por ora
 * depende só de `shared`; a aresta `hotel` → `files` (logotipo, H8) chega com o módulo `files` (E08).
 *
 * A raiz do pacote é a API pública: [HotelApi] (leituras para os outros módulos e o avanço da data
 * operacional, que só o fechamento do dia chama), o evento [BusinessDateAdvanced], os tipos de
 * valor da configuração, os códigos de erro ([HotelErrorCodes]) e as permissões
 * ([HotelPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Hotel and business date", allowedDependencies = ["shared"])
class HotelModule
