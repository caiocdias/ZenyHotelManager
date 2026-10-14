package archfixtures.unaudited.booking.application

import com.zeny.hotel.shared.Audited
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Exemplo que viola a regra de auditoria: escritas sem `@Audited` (e os casos que a regra aceita). */
@Service
class UnauditedBookingService {
    private val bookings = mutableListOf<String>()

    @Audited("booking.booking.book")
    @Transactional
    fun book(id: String) {
        bookings += id
    }

    @Transactional
    fun cancel(id: String) {
        bookings -= id
    }

    @Transactional(rollbackFor = [IllegalStateException::class])
    fun reschedule(id: String) {
        bookings += id
    }

    @Transactional(readOnly = true)
    fun find(id: String): String? = bookings.firstOrNull { it == id }
}

/** `@Transactional` na classe: todo método público é uma escrita, salvo o `readOnly`. */
@Service
@Transactional
class ClassLevelTransactionService {
    private val confirmed = mutableListOf<String>()

    fun confirm(id: String) {
        confirmed += id
    }

    @Transactional(readOnly = true)
    fun list(): List<String> = confirmed.toList()
}

/** `@jakarta.transaction.Transactional`, no método e na classe: também é escrita, e sem `@Audited` viola. */
@Service
class JakartaTransactionService {
    private val archived = mutableListOf<String>()

    @jakarta.transaction.Transactional
    fun archive(id: String) {
        archived += id
    }

    @Audited("booking.booking.restore")
    @jakarta.transaction.Transactional
    fun restore(id: String) {
        archived -= id
    }
}

@Service
@jakarta.transaction.Transactional
class JakartaClassLevelService {
    private val closed = mutableListOf<String>()

    fun close(id: String) {
        closed += id
    }
}
