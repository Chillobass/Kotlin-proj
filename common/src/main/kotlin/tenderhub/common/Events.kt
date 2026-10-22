package tenderhub.common

import java.time.Instant

interface DomainEvent {
    val occurredAt: Instant
}

fun interface EventPublisher {
    fun publish(event: DomainEvent)
}
