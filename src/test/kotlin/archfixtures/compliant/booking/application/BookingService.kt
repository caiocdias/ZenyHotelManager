package archfixtures.compliant.booking.application

import archfixtures.compliant.billing.BillingApi
import archfixtures.compliant.booking.BookingApi
import archfixtures.compliant.booking.BookingView
import archfixtures.compliant.booking.domain.Booking
import archfixtures.compliant.booking.domain.BookingStatus
import org.springframework.stereotype.Service

/** Caso de uso de exemplo: usa o próprio `domain` e só a API pública de outro módulo. */
@Service
class BookingService(
    private val billing: BillingApi,
) : BookingApi {
    override fun find(id: String): BookingView? {
        val booking = Booking(id, BookingStatus.CONFIRMED)
        billing.charge(booking.id, cents = 0)
        return BookingView(booking.id)
    }
}
