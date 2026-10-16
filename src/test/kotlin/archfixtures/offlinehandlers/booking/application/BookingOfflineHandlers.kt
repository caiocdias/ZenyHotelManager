package archfixtures.offlinehandlers.booking.application

import com.zeny.hotel.shared.Audited
import com.zeny.hotel.shared.OfflineCommand
import com.zeny.hotel.shared.OfflineCommandHandler
import com.zeny.hotel.shared.OfflineCommandOutcome
import com.zeny.hotel.shared.OfflineCommandType
import org.springframework.transaction.annotation.Transactional

data class BookingPayload(
    val id: String,
)

/** Exemplo que cumpre a regra: `handle` auditado e numa transação de escrita. */
class AuditedBookingHandler : OfflineCommandHandler<BookingPayload> {
    override val type = OfflineCommandType("booking.confirm")
    override val payloadType = BookingPayload::class.java

    @Audited("booking.booking.confirm")
    @Transactional
    override fun handle(command: OfflineCommand<BookingPayload>) = OfflineCommandOutcome.APPLIED
}

/** Viola: sem `@Audited`. */
class UnauditedBookingHandler : OfflineCommandHandler<BookingPayload> {
    override val type = OfflineCommandType("booking.cancel")
    override val payloadType = BookingPayload::class.java

    @Transactional
    override fun handle(command: OfflineCommand<BookingPayload>) = OfflineCommandOutcome.APPLIED
}

/** Viola: auditado, mas sem transação (o `@Audited` exige a transação de escrita). */
class NonTransactionalBookingHandler : OfflineCommandHandler<BookingPayload> {
    override val type = OfflineCommandType("booking.archive")
    override val payloadType = BookingPayload::class.java

    @Audited("booking.booking.archive")
    override fun handle(command: OfflineCommand<BookingPayload>) = OfflineCommandOutcome.APPLIED
}

/** Viola: transação só de leitura. */
class ReadOnlyBookingHandler : OfflineCommandHandler<BookingPayload> {
    override val type = OfflineCommandType("booking.peek")
    override val payloadType = BookingPayload::class.java

    @Audited("booking.booking.peek")
    @Transactional(readOnly = true)
    override fun handle(command: OfflineCommand<BookingPayload>) = OfflineCommandOutcome.APPLIED
}
