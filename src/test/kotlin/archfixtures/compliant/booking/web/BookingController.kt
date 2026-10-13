package archfixtures.compliant.booking.web

import archfixtures.compliant.booking.BookingView
import archfixtures.compliant.booking.application.BookingService

/** Controller de exemplo: só fala com `application` e com os DTOs públicos do módulo. */
class BookingController(
    private val service: BookingService,
) {
    fun get(id: String): BookingView? = service.find(id)
}
