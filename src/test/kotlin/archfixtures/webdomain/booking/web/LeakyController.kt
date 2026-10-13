package archfixtures.webdomain.booking.web

import archfixtures.webdomain.booking.domain.Booking

/** Violação proposital: `web` usando `domain` sem passar por `application`. */
class LeakyController {
    fun get(id: String): Booking = Booking(id)
}
