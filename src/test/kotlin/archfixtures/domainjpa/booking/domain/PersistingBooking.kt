package archfixtures.domainjpa.booking.domain

import jakarta.persistence.EntityManager

/** Violação proposital: `domain` usando a API de persistência, não só anotações. */
class PersistingBooking(
    private val entityManager: EntityManager,
) {
    fun save() = entityManager.persist(this)
}
