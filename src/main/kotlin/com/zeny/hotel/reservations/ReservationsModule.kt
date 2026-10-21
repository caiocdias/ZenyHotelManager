package com.zeny.hotel.reservations

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Declaração do módulo `reservations` (ARCHITECTURE §3.1, SPEC §5.6, §5.7 e §8.2.6; camada Hospedagem): inventário por
 * tipo de UH e data (M4 E05); disponibilidade, reservas, alocação e bloqueios nas etapas seguintes. Pode depender de
 * `shared`, `hotel` (data operacional, só pela `HotelApi`) e `rooms` (UHs ativas e períodos OOO, só pela `RoomsApi`, e
 * os eventos `RoomRegistryChanged` e `OutOfOrderChanged`); não conhece `iam`.
 *
 * A raiz do pacote é a API pública: [ReservationsApi], os tipos de valor, os códigos de erro
 * ([ReservationsErrorCodes]), as permissões ([ReservationsPermissions]) e os eventos ([AvailabilityChanged]).
 */
@PackageInfo
@ApplicationModule(displayName = "Reservations and availability", allowedDependencies = ["shared", "hotel", "rooms"])
class ReservationsModule
