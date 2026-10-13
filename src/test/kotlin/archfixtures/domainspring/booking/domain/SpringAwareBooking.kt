package archfixtures.domainspring.booking.domain

import org.springframework.context.ApplicationEventPublisher

/** Violação proposital: `domain` dependendo de Spring. */
class SpringAwareBooking(
    private val publisher: ApplicationEventPublisher,
) {
    fun confirm() = publisher.publishEvent("confirmed")
}
