package archfixtures.compliant.billing

/** API pública do módulo fictício `billing` (raiz do pacote). */
interface BillingApi {
    fun charge(
        bookingId: String,
        cents: Long,
    )
}
