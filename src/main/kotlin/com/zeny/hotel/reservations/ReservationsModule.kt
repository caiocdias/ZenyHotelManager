package com.zeny.hotel.reservations

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `reservations` (ARCHITECTURE §3.1, SPEC §5.6, §5.7 e §8.2.6; camada Hospedagem): inventário por
 * tipo de UH e data (M4 E05); disponibilidade em calendário e cotação (E06); reservas, alocação e bloqueios nas etapas
 * seguintes. Pode depender de `shared`, `hotel` (data operacional, só pela `HotelApi`), `rooms` (UHs ativas e
 * períodos OOO, só pela `RoomsApi`, e os eventos `RoomRegistryChanged` e `OutOfOrderChanged`) e, desde a E06, `rates`
 * (preço, vendabilidade, cotação e calendário de preços e restrições, só pela `RatesApi`) e, desde a E07, `guests`
 * (hóspedes pela `GuestsApi`, o evento `GuestsMerged` e as portas de privacidade) e `billing` (a conta da reserva pela
 * `BillingApi`) e, desde a E10, `notifications` (a notificação de realocação pela `NotificationsApi` e o catálogo de
 * tipos, H11); não conhece `iam`.
 *
 * A raiz do pacote é a API pública: [ReservationsApi], os tipos de valor, os códigos de erro
 * ([ReservationsErrorCodes]), as permissões ([ReservationsPermissions]) e os eventos ([AvailabilityChanged],
 * [ReservationCreated]).
 */
@PackageInfo
@ApplicationModule(
    displayName = "Reservations and availability",
    allowedDependencies = ["shared", "hotel", "rooms", "rates", "guests", "billing", "notifications"],
)
class ReservationsModule
