package com.zeny.hotel.frontdesk

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `frontdesk` (ARCHITECTURE §3.1 e §6.1, SPEC §5.8; camada Hospedagem): a recepção, que
 * **orquestra** os módulos de baixo numa transação só, sem estado próprio no M5 E02. Desde a E02, o check-in
 * (documentos dos hóspedes, menores, UH pronta, conta da reserva, caução opcional, transição da reserva e UH ocupada),
 * o desfazer check-in (sensível) e a cotação de early check-in e late check-out; desde a E03, o walk-in (a reserva, a
 * alocação e o check-in numa transação). Pode depender de `shared`, `hotel` (a
 * data operacional, o fuso e a política de early/late, só pela `HotelApi`), `reservations` (a reserva, a alocação e as
 * transições de hospedagem, só pela `ReservationsApi`), `rooms` (RN-05 e a ocupação, só pela `RoomsApi`), `guests` (a
 * identificação sem número e o alerta da lista de restrição, só pela `GuestsApi`; **nunca** `fnrhData`), `billing` (a
 * conta da reserva e a caução, só pela `BillingApi`), `files` (o anexo da comprovação do menor, só pela `FilesApi`) e,
 * desde a E03, `rates` (só os tipos públicos que a `ReservationsApi` usa: o id do plano do walk-in, `RatePlanId`, e o
 * tipo de garantia da política copiada, `GuaranteeType`; nenhuma chamada à `RatesApi`); nunca `fnrh` (camada acima:
 * ele reage aos eventos de `reservations`) nem `iam`.
 *
 * A raiz do pacote é a API pública: as permissões ([FrontdeskPermissions]) e os códigos de erro
 * ([FrontdeskErrorCodes]).
 */
@PackageInfo
@ApplicationModule(
    displayName = "Front desk",
    allowedDependencies = ["shared", "hotel", "reservations", "rooms", "guests", "billing", "files", "rates"],
)
class FrontdeskModule
