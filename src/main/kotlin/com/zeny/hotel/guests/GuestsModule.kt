package com.zeny.hotel.guests

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `guests` (ARCHITECTURE §3.1, SPEC §5.5 e §8.2.4; camada Cadastros): perfis de
 * hóspedes com os campos da FNRH, documentos cifrados com índice cego, dados sensíveis da FNRH, lista de
 * restrição, empresas, mesclagem de duplicados e a LGPD (consentimentos, anexos, exportação e anonimização).
 * Depende de `shared` e de `files` (os anexos, só pela `FilesApi`); não conhece reservas, contas nem o `iam`.
 *
 * A raiz do pacote é a API pública: [GuestsApi] (leituras para os módulos de cima), os ids e tipos de
 * valor, os eventos ([GuestsMerged]), as portas que os módulos de cima implementam
 * ([GuestPersonalDataContributor], [GuestActiveStayCheck]), as finalidades de anexo ([GuestsFilePurposes]), os
 * códigos de erro ([GuestsErrorCodes]) e as permissões ([GuestsPermissions]).
 */
@PackageInfo
@ApplicationModule(displayName = "Guests and companies", allowedDependencies = ["shared", "files"])
class GuestsModule
