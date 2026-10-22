package tenderhub.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tenderhub.bidding.api.BidderDirectory
import tenderhub.bidding.api.Standing
import tenderhub.bidding.api.TenderResultsPublished
import tenderhub.common.Actors
import tenderhub.common.InPlaceTransactionRunner
import tenderhub.common.MutableClock
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.notifications.application.NotificationRepository
import tenderhub.notifications.application.NotificationService
import tenderhub.notifications.domain.Notification
import tenderhub.notifications.domain.NotificationType
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderSubmitted
import tenderhub.tenders.domain.TenderStatus
import tenderhub.users.api.UserDirectory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class NotificationServiceTest {
    private val clock = MutableClock()
    private val repo = InMemoryNotificationRepository()
    private val admins = listOf(UUID.randomUUID(), UUID.randomUUID())
    private val participants = mutableListOf<UUID>()
    private val users = object : UserDirectory {
        override fun adminIds() = admins

        override fun displayNames(ids: Collection<UUID>) = emptyMap<UUID, String>()
    }
    private val bidders = object : BidderDirectory {
        override fun activeParticipantIds(tenderId: UUID) = participants.toList()
    }
    private val service = NotificationService(repo, users, bidders, InPlaceTransactionRunner, clock)

    private val ownerId = UUID.randomUUID()
    private val tenderId = UUID.randomUUID()

    @Test
    fun `admins are told about a new tender on review`() {
        service.onTenderSubmitted(TenderSubmitted(tenderId, ownerId, "Ремонт", clock.instant()))
        assertEquals(admins.toSet(), repo.rows.values.map { it.recipientId }.toSet())
        assertTrue(repo.rows.values.all { it.type == NotificationType.TENDER_SUBMITTED_FOR_REVIEW })
    }

    @Test
    fun `participants and owner learn about admin cancellation`() {
        participants += listOf(UUID.randomUUID(), UUID.randomUUID())
        service.onTenderCancelled(TenderCancelled(tenderId, ownerId, "Ремонт", "Мошенничество", true, clock.instant()))
        assertEquals((participants + ownerId).toSet(), repo.rows.values.map { it.recipientId }.toSet())
    }

    @Test
    fun `everyone learns their place when results are published`() {
        val winner = UUID.randomUUID()
        val second = UUID.randomUUID()
        service.onResultsPublished(
            TenderResultsPublished(
                tenderId, ownerId, "Ремонт", TenderStatus.COMPLETED,
                listOf(Standing(winner, 1, BigDecimal("90.00")), Standing(second, 2, BigDecimal("80.00"))),
                clock.instant(),
            ),
        )
        val byRecipient = repo.rows.values.associateBy { it.recipientId }
        assertEquals(NotificationType.TENDER_COMPLETED, byRecipient.getValue(ownerId).type)
        assertTrue(byRecipient.getValue(winner).message.contains("победила"))
        assertTrue(byRecipient.getValue(second).message.contains("2-м месте"))
    }

    @Test
    fun `a user can read only own notifications`() {
        service.onTenderSubmitted(TenderSubmitted(tenderId, ownerId, "Ремонт", clock.instant()))
        val notification = repo.rows.values.first()
        assertThrows<NotFoundException> { service.markRead(Actors.user(), notification.id) }
        val read = service.markRead(Actors.user(notification.recipientId), notification.id)
        assertTrue(read.isRead)
    }
}

class InMemoryNotificationRepository : NotificationRepository {
    val rows = linkedMapOf<UUID, Notification>()

    override fun insertAll(notifications: List<Notification>) {
        notifications.forEach { rows[it.id] = it }
    }

    override fun update(notification: Notification) {
        rows[notification.id] = notification
    }

    override fun findById(id: UUID) = rows[id]

    override fun findByRecipient(recipientId: UUID, unreadOnly: Boolean, page: PageRequest): Page<Notification> {
        val found = rows.values.filter { it.recipientId == recipientId && (!unreadOnly || !it.isRead) }
        return Page.of(found, page, found.size.toLong())
    }

    override fun countUnread(recipientId: UUID) = rows.values.count { it.recipientId == recipientId && !it.isRead }.toLong()

    override fun markAllRead(recipientId: UUID, now: Instant): Int {
        val unread = rows.values.filter { it.recipientId == recipientId && !it.isRead }
        unread.forEach { it.markRead(now) }
        return unread.size
    }
}
