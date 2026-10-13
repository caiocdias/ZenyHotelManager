package archfixtures.domaininfra.booking.domain

import archfixtures.domaininfra.booking.infrastructure.BookingRecord

/** Violação proposital: `domain` importando uma classe de `infrastructure`. */
class RecordAwareBooking(
    val record: BookingRecord,
)
