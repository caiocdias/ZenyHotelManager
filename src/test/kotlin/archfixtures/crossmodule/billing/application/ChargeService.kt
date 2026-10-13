package archfixtures.crossmodule.billing.application

import archfixtures.crossmodule.booking.domain.Booking

/** Violação proposital: `billing` usando uma camada interna de `booking`. */
class ChargeService {
    fun charge(booking: Booking): String = booking.id
}
