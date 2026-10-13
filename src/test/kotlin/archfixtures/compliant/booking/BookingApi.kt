package archfixtures.compliant.booking

/** API pública do módulo fictício `booking` (raiz do pacote). */
interface BookingApi {
    fun find(id: String): BookingView?
}

data class BookingView(
    val id: String,
)
