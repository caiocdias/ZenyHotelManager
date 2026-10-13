package archfixtures.domainweb.booking.domain

import archfixtures.domainweb.booking.web.BookingRequest

/** Violação proposital: `domain` importando uma classe de `web`. */
class WebAwareBooking(
    val request: BookingRequest,
)
