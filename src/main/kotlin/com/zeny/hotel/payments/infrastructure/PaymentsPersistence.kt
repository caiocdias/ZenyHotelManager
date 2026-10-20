package com.zeny.hotel.payments.infrastructure

import com.zeny.hotel.payments.PaymentIntentId
import com.zeny.hotel.payments.application.PaymentIntentStore
import com.zeny.hotel.payments.domain.PaymentIntent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

interface PaymentIntentJpaRepository : JpaRepository<PaymentIntent, UUID>

/** As intenções por JPA, na transação corrente. */
@Component
class JpaPaymentIntentStore(
    private val intents: PaymentIntentJpaRepository,
) : PaymentIntentStore {
    override fun intent(id: PaymentIntentId): PaymentIntent? = intents.findById(id.value).orElse(null)

    override fun add(intent: PaymentIntent) {
        intents.save(intent)
    }

    override fun flush() = intents.flush()
}
