package tenderhub.notifications.adapter.persistence

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.jdbc.enum
import tenderhub.common.jdbc.instant
import tenderhub.common.jdbc.instantOrNull
import tenderhub.common.jdbc.toDb
import tenderhub.common.jdbc.toDbOrNull
import tenderhub.common.jdbc.uuid
import tenderhub.common.jdbc.uuidOrNull
import tenderhub.notifications.application.NotificationRepository
import tenderhub.notifications.domain.Notification
import java.time.Instant
import java.util.UUID

@Repository
class JdbcNotificationRepository(
    private val jdbc: JdbcClient,
    private val namedJdbc: NamedParameterJdbcTemplate,
) : NotificationRepository {
    private val mapper = RowMapper { rs, _ ->
        Notification(
            id = rs.uuid("id"),
            recipientId = rs.uuid("recipient_id"),
            type = rs.enum("type"),
            tenderId = rs.uuidOrNull("tender_id"),
            message = rs.getString("message"),
            createdAt = rs.instant("created_at"),
            readAt = rs.instantOrNull("read_at"),
        )
    }

    override fun insertAll(notifications: List<Notification>) {
        if (notifications.isEmpty()) return
        val batch = notifications.map<Notification, SqlParameterSource> {
            MapSqlParameterSource()
                .addValue("id", it.id)
                .addValue("recipientId", it.recipientId)
                .addValue("type", it.type.name)
                .addValue("tenderId", it.tenderId)
                .addValue("message", it.message.take(2000))
                .addValue("createdAt", it.createdAt.toDb())
                .addValue("readAt", it.readAt.toDbOrNull())
        }.toTypedArray()
        namedJdbc.batchUpdate(
            """
            INSERT INTO notifications (id, recipient_id, type, tender_id, message, created_at, read_at)
            VALUES (:id, :recipientId, :type, :tenderId, :message, :createdAt, :readAt)
            """.trimIndent(),
            batch,
        )
    }

    override fun update(notification: Notification) {
        jdbc.sql("UPDATE notifications SET read_at = :readAt WHERE id = :id")
            .param("id", notification.id)
            .param("readAt", notification.readAt.toDbOrNull())
            .update()
    }

    override fun findById(id: UUID): Notification? =
        jdbc.sql("SELECT * FROM notifications WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    override fun findByRecipient(recipientId: UUID, unreadOnly: Boolean, page: PageRequest): Page<Notification> {
        val where = "recipient_id = :recipientId" + if (unreadOnly) " AND read_at IS NULL" else ""
        val items = jdbc.sql("SELECT * FROM notifications WHERE $where ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
            .param("recipientId", recipientId)
            .param("limit", page.size)
            .param("offset", page.offset)
            .query(mapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM notifications WHERE $where")
            .param("recipientId", recipientId)
            .query(Long::class.javaObjectType)
            .single()
        return Page.of(items, page, total)
    }

    override fun countUnread(recipientId: UUID): Long =
        jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_id = :recipientId AND read_at IS NULL")
            .param("recipientId", recipientId)
            .query(Long::class.javaObjectType)
            .single()

    override fun markAllRead(recipientId: UUID, now: Instant): Int =
        jdbc.sql("UPDATE notifications SET read_at = :now WHERE recipient_id = :recipientId AND read_at IS NULL")
            .param("recipientId", recipientId)
            .param("now", now.toDb())
            .update()
}
