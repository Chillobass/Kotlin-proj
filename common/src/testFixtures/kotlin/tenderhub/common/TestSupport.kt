package tenderhub.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class MutableClock(private var now: Instant = Instant.parse("2026-01-10T10:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun set(instant: Instant) {
        now = instant
    }

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

object InPlaceTransactionRunner : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()

    override fun <T> readOnly(block: () -> T): T = block()
}

class RecordingEventPublisher : EventPublisher {
    val events = mutableListOf<DomainEvent>()

    override fun publish(event: DomainEvent) {
        events += event
    }

    inline fun <reified E : DomainEvent> ofType(): List<E> = events.filterIsInstance<E>()
}

object Actors {
    fun user(id: UUID = UUID.randomUUID()) = Actor(id, Role.USER, blocked = false)

    fun blockedUser(id: UUID = UUID.randomUUID()) = Actor(id, Role.USER, blocked = true)

    fun admin(id: UUID = UUID.randomUUID()) = Actor(id, Role.ADMIN, blocked = false)
}
