package archfixtures.webinfra.booking.web

import archfixtures.webinfra.booking.infrastructure.BookingRepository

/** Violação proposital: `web` usando `infrastructure` sem passar por `application`. */
class RepositoryController(
    private val repository: BookingRepository,
) {
    fun exists(id: String): Boolean = repository.exists(id)
}
