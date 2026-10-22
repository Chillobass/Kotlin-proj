package tenderhub.users.api

import tenderhub.common.DomainEvent
import java.time.Instant
import java.util.UUID

interface UserDirectory {
    fun adminIds(): List<UUID>

    fun displayNames(ids: Collection<UUID>): Map<UUID, String>
}

data class UserBlocked(
    val userId: UUID,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent
