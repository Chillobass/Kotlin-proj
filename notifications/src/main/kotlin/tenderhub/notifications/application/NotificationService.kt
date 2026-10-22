package tenderhub.notifications.application

import org.slf4j.LoggerFactory
import tenderhub.bidding.api.BidderDirectory
import tenderhub.bidding.api.TenderResultsPublished
import tenderhub.common.Actor
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.TransactionRunner
import tenderhub.notifications.domain.Notification
import tenderhub.notifications.domain.NotificationType
import tenderhub.tenders.api.TenderApproved
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderRejected
import tenderhub.tenders.api.TenderSubmitted
import tenderhub.tenders.domain.TenderStatus
import tenderhub.users.api.UserDirectory
import java.time.Clock
import java.util.UUID

interface NotificationRepository {
    fun insertAll(notifications: List<Notification>)

    fun update(notification: Notification)

    fun findById(id: UUID): Notification?

    fun findByRecipient(recipientId: UUID, unreadOnly: Boolean, page: PageRequest): Page<Notification>

    fun countUnread(recipientId: UUID): Long

    fun markAllRead(recipientId: UUID, now: java.time.Instant): Int
}

class NotificationService(
    private val notifications: NotificationRepository,
    private val users: UserDirectory,
    private val bidders: BidderDirectory,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(actor: Actor, unreadOnly: Boolean, page: PageRequest): Page<Notification> =
        tx.readOnly { notifications.findByRecipient(actor.userId, unreadOnly, page) }

    fun unreadCount(actor: Actor): Long = tx.readOnly { notifications.countUnread(actor.userId) }

    fun markRead(actor: Actor, id: UUID): Notification = tx.inTransaction {
        val notification = notifications.findById(id)?.takeIf { it.recipientId == actor.userId }
            ?: throw NotFoundException("NOTIFICATION_NOT_FOUND", "Уведомление $id не найдено")
        notification.markRead(clock.instant())
        notifications.update(notification)
        notification
    }

    fun markAllRead(actor: Actor): Int = tx.inTransaction { notifications.markAllRead(actor.userId, clock.instant()) }

    fun onTenderSubmitted(event: TenderSubmitted) = send(
        users.adminIds(),
        NotificationType.TENDER_SUBMITTED_FOR_REVIEW,
        event.tenderId,
        "Новый тендер на модерации: «${event.title}»",
    )

    fun onTenderApproved(event: TenderApproved) = send(
        listOf(event.ownerId),
        NotificationType.TENDER_APPROVED,
        event.tenderId,
        "Тендер «${event.title}» одобрен и опубликован. Приём заявок до ${event.deadline}",
    )

    fun onTenderRejected(event: TenderRejected) = send(
        listOf(event.ownerId),
        NotificationType.TENDER_REJECTED,
        event.tenderId,
        "Тендер «${event.title}» отклонён модератором. Причина: ${event.reason}",
    )

    fun onTenderCancelled(event: TenderCancelled) {
        val message = "Тендер «${event.title}» отменён. Причина: ${event.reason}"
        val recipients = bidders.activeParticipantIds(event.tenderId) + if (event.byAdmin) listOf(event.ownerId) else emptyList()
        send(recipients, NotificationType.TENDER_CANCELLED, event.tenderId, message)
    }

    fun onResultsPublished(event: TenderResultsPublished) {
        if (event.status == TenderStatus.FAILED) {
            send(
                listOf(event.ownerId),
                NotificationType.TENDER_FAILED,
                event.tenderId,
                "Тендер «${event.title}» завершён без заявок и признан несостоявшимся",
            )
            return
        }
        send(
            listOf(event.ownerId),
            NotificationType.TENDER_COMPLETED,
            event.tenderId,
            "Тендер «${event.title}» завершён, получено заявок: ${event.standings.size}. Рейтинг опубликован",
        )
        val total = event.standings.size
        val now = clock.instant()
        val perParticipant = event.standings.map { standing ->
            val text = if (standing.place == 1) {
                "Ваша заявка победила в тендере «${event.title}» (балл ${standing.score})"
            } else {
                "Тендер «${event.title}» завершён: ваша заявка на ${standing.place}-м месте из $total (балл ${standing.score})"
            }
            Notification.create(standing.participantId, NotificationType.BID_RESULT, event.tenderId, text, now)
        }
        tx.inTransaction { notifications.insertAll(perParticipant) }
    }

    private fun send(recipients: Collection<UUID>, type: NotificationType, tenderId: UUID?, message: String) {
        val distinct = recipients.distinct()
        if (distinct.isEmpty()) return
        val now = clock.instant()
        tx.inTransaction { notifications.insertAll(distinct.map { Notification.create(it, type, tenderId, message, now) }) }
        log.debug("Notification {} sent to {} recipient(s)", type, distinct.size)
    }
}
