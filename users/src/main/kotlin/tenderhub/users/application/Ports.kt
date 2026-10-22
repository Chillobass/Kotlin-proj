package tenderhub.users.application

import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.users.domain.User
import java.util.UUID

interface UserRepository {
    fun insert(user: User)

    fun update(user: User)

    fun findById(id: UUID): User?

    fun findByIdForUpdate(id: UUID): User?

    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    fun findAll(page: PageRequest): Page<User>

    fun findAdminIds(): List<UUID>

    fun findDisplayNames(ids: Collection<UUID>): Map<UUID, String>
}

interface PasswordHasher {
    fun hash(raw: String): String

    fun matches(raw: String, hash: String): Boolean
}
