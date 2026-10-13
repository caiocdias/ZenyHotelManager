package archfixtures.domainapp.booking.domain

import archfixtures.domainapp.booking.application.BookingCommand

/** Violação proposital: `domain` importando uma classe de `application`. */
class CommandAwareBooking(
    val command: BookingCommand,
)
