package archfixtures.compliant.booking.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id

/** Agregado de exemplo: anotações JPA (e seus enums) são toleradas em `domain`. */
@Entity
class Booking(
    @Id val id: String,
    @Enumerated(EnumType.STRING) val status: BookingStatus,
)

enum class BookingStatus { CONFIRMED, CANCELLED }
