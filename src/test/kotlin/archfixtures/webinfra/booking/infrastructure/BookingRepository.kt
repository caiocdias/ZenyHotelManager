package archfixtures.webinfra.booking.infrastructure

class BookingRepository {
    fun exists(id: String): Boolean = id.isNotEmpty()
}
