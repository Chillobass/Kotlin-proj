package tenderhub.notifications.domain

import java.time.Instant
import java.util.UUID

enum class NotificationType {
    TENDER_SUBMITTED_FOR_REVIEW,
    TENDER_APPROVED,
    TENDER_REJECTED,
    TENDER_CANCELLED,
    TENDER_COMPLETED,
    TENDER_FAILED,
    BID_RESULT,
}

class Notification(
    val id: UUID,
    val recipientId: UUID,
    val type: NotificationType,
    val tenderId: UUID?,
    val message: String,
    val createdAt: Instant,
    readAt: Instant?,
) {
    var readAt: Instant? = readAt
        private set

    val isRead: Boolean get() = readAt != null

    fun markRead(now: Instant) {
        if (readAt == null) readAt = now
    }

    companion object {
        fun create(recipientId: UUID, type: NotificationType, tenderId: UUID?, message: String, now: Instant) =
            Notification(UUID.randomUUID(), recipientId, type, tenderId, message, now, null)
    }
}
