package archfixtures.compliant.booking.infrastructure

import archfixtures.compliant.booking.domain.Booking
import jakarta.persistence.EntityManager

/** Adaptador de exemplo: a API de persistência fica em `infrastructure`. */
class BookingRepository(
    private val entityManager: EntityManager,
) {
    fun find(id: String): Booking? = entityManager.find(Booking::class.java, id)
}
