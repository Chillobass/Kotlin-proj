package tenderhub.notifications.adapter.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tenderhub.common.Actor
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.notifications.application.NotificationService
import tenderhub.notifications.domain.Notification
import tenderhub.notifications.domain.NotificationType
import java.time.Instant
import java.util.UUID

data class NotificationResponse(
    val id: UUID,
    val type: NotificationType,
    val tenderId: UUID?,
    val message: String,
    val createdAt: Instant,
    val read: Boolean,
) {
    companion object {
        fun from(n: Notification) = NotificationResponse(n.id, n.type, n.tenderId, n.message, n.createdAt, n.isRead)
    }
}

data class UnreadCountResponse(val unread: Long)

data class MarkedReadResponse(val marked: Int)

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val notificationService: NotificationService) {
    @GetMapping
    fun list(
        actor: Actor,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<NotificationResponse> =
        notificationService.list(actor, unreadOnly, PageRequest(page, size)).map(NotificationResponse::from)

    @GetMapping("/unread-count")
    fun unreadCount(actor: Actor) = UnreadCountResponse(notificationService.unreadCount(actor))

    @PostMapping("/{id}/read")
    fun markRead(actor: Actor, @PathVariable id: UUID): NotificationResponse =
        NotificationResponse.from(notificationService.markRead(actor, id))

    @PostMapping("/read-all")
    fun markAllRead(actor: Actor) = MarkedReadResponse(notificationService.markAllRead(actor))
}
