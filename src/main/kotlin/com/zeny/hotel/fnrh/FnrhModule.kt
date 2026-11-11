package com.zeny.hotel.fnrh

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `fnrh` (ARCHITECTURE §3.1 e §12, SPEC §5.9; camada acima de Hospedagem): a integração do
 * servidor com a FNRH do Serpro (API v2.4, `docs/fnrh/api-fnrh-v2.4.md`). Desde a E07, a configuração da integração
 * (ambiente, usuário, senha e CPF solicitante padrão, cifrados), a porta de conexão para o adaptador e as
 * permissões, o mapeamento dos dados do hóspede e a validação local. Pode depender de `shared` (o CPF e a cifragem de
 * campo) e de `guests` (só pela `GuestsApi`, que entrega os dados do hóspede); nunca de `iam`, `frontdesk` nem de
 * tabela de outro schema.
 *
 * A raiz do pacote é a API pública: o ambiente ([FnrhEnvironment]), as permissões ([FnrhPermissions]) e os códigos de
 * erro ([FnrhErrorCodes] e [FnrhFieldCodes]).
 */
@PackageInfo
@ApplicationModule(displayName = "FNRH", allowedDependencies = ["shared", "guests", "reservations", "notifications"])
class FnrhModule
