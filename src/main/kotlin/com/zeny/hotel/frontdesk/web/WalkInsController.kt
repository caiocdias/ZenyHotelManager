package com.zeny.hotel.frontdesk.web

import com.zeny.hotel.frontdesk.application.FrontdeskWalkInService
import com.zeny.hotel.shared.ApiV1Controller
import com.zeny.hotel.shared.ETags
import com.zeny.hotel.shared.Idempotent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import java.net.URI

/** O walk-in da recepção (M5 E03; RF-REC-02): a reserva, a alocação e o check-in numa transação só. */
@ApiV1Controller
@RequestMapping("/walk-ins")
@Tag(name = FRONTDESK_TAG, description = FRONTDESK_TAG_DESCRIPTION)
class WalkInsController(
    private val walkIns: FrontdeskWalkInService,
) {
    @PostMapping
    @Idempotent
    @Operation(
        operationId = "createWalkIn",
        summary = "Fazer o walk-in: criar a reserva, alocar a UH e fazer o check-in",
        description =
            "Numa transação só (tudo ou nada; ARCHITECTURE §6.1): cria a reserva agrupadora com uma reserva " +
                "de origem `FRONT_DESK` (canal `DIRECT`), chegada na data operacional e o preço calculado pela " +
                "tarifa (sem diária editada nem desconto), com os hóspedes já cadastrados; a pré-reserva (plano " +
                "com garantia) é confirmada com a garantia que a política pede (cartão; o depósito exige o " +
                "depósito na conta, 422 `DEPOSIT_INSUFFICIENT`); aloca a UH escolhida e faz o check-in como " +
                "`POST /api/v1/reservations/{id}/check-in` (publica `ReservationCreated` e `GuestCheckedIn`). " +
                "Exige `frontdesk.stays.checkin`, `reservations.reservations.manage`, " +
                "`reservations.assignments.manage` e `Idempotency-Key`. Recusas, sem nada gravado (nenhuma " +
                "reserva, conta, unidade do inventário, alocação, caução nem ocupação): UH inexistente 400 " +
                "`UNKNOWN_ROOM`; hóspede inexistente 400 `UNKNOWN_GUEST`, anonimizado 422 `GUEST_ANONYMIZED`, " +
                "sem os dados mínimos 422 `GUEST_DATA_INCOMPLETE`; anexo da comprovação do menor inválido 422 " +
                "`MINOR_AUTHORIZATION_ATTACHMENT_INVALID`; formato da reserva 400 `BOOKING_INVALID` ou " +
                "`STAY_INVALID`, plano ou tipo inexistente 400 `UNKNOWN_RATE_PLAN`/`UNKNOWN_ROOM_TYPE` (com o " +
                "campo do pedido); estadia não vendável no canal 422 com o código do primeiro motivo do `rates` " +
                "e todos em `refusals`; sem unidade do tipo em alguma noite 409 `ROOM_TYPE_UNAVAILABLE`; UH de " +
                "outro tipo 422 `ROOM_TYPE_MISMATCH`, desativada 422 `ROOM_INACTIVE`, em OOO 422 " +
                "`ROOM_OUT_OF_ORDER`, já alocada a outra reserva 409 `ROOM_ALREADY_ASSIGNED`; UH não pronta 409 " +
                "`ROOM_NOT_READY`; as recusas do check-in da reserva (RN-24: menor sem comprovação 422 " +
                "`MINOR_AUTHORIZATION_REQUIRED`…) e da caução (400 `PRE_AUTHORIZATION_INVALID` com o campo em " +
                "`preAuthorization.…`). Responde 201 com o `Location` da reserva e o `ETag` dela.",
    )
    fun walkIn(
        @Valid @RequestBody request: WalkInRequest,
    ): ResponseEntity<WalkInResponse> {
        val outcome = walkIns.walkIn(request.toCommand())
        val reservation = outcome.checkIn.reservation
        return ResponseEntity
            .created(URI.create("/api/v1/reservations/${reservation.id.value}"))
            .eTag(ETags.of(reservation.version))
            .body(WalkInResponse.of(outcome))
    }
}
