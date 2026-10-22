package tenderhub.users.adapter.persistence

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tenderhub.common.ConflictException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.Role
import tenderhub.common.jdbc.enum
import tenderhub.common.jdbc.instant
import tenderhub.common.jdbc.toDb
import tenderhub.common.jdbc.uuid
import tenderhub.users.application.UserRepository
import tenderhub.users.domain.User
import java.util.UUID

@Repository
class JdbcUserRepository(private val jdbc: JdbcClient) : UserRepository {
    private val mapper = RowMapper { rs, _ ->
        User(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            passwordHash = rs.getString("password_hash"),
            role = rs.enum<Role>("role"),
            blocked = rs.getBoolean("blocked"),
            blockReason = rs.getString("block_reason"),
            createdAt = rs.instant("created_at"),
        )
    }

    override fun insert(user: User) {
        try {
            jdbc.sql(
                """
                INSERT INTO users (id, email, display_name, password_hash, role, blocked, block_reason, created_at)
                VALUES (:id, :email, :displayName, :passwordHash, :role, :blocked, :blockReason, :createdAt)
                """.trimIndent(),
            )
                .param("id", user.id)
                .param("email", user.email)
                .param("displayName", user.displayName)
                .param("passwordHash", user.passwordHash)
                .param("role", user.role.name)
                .param("blocked", user.blocked)
                .param("blockReason", user.blockReason)
                .param("createdAt", user.createdAt.toDb())
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("EMAIL_TAKEN", "Пользователь с таким e-mail уже зарегистрирован")
        }
    }

    override fun update(user: User) {
        jdbc.sql(
            """
            UPDATE users
            SET display_name = :displayName, blocked = :blocked, block_reason = :blockReason, updated_at = now()
            WHERE id = :id
            """.trimIndent(),
        )
            .param("id", user.id)
            .param("displayName", user.displayName)
            .param("blocked", user.blocked)
            .param("blockReason", user.blockReason)
            .update()
    }

    override fun findById(id: UUID): User? =
        jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    override fun findByIdForUpdate(id: UUID): User? =
        jdbc.sql("SELECT * FROM users WHERE id = :id FOR UPDATE").param("id", id).query(mapper).optional().orElse(null)

    override fun findByEmail(email: String): User? =
        jdbc.sql("SELECT * FROM users WHERE email = :email").param("email", email).query(mapper).optional().orElse(null)

    override fun existsByEmail(email: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE email = :email)")
            .param("email", email)
            .query(Boolean::class.javaObjectType)
            .single()

    override fun findAll(page: PageRequest): Page<User> {
        val items = jdbc.sql("SELECT * FROM users ORDER BY created_at, id LIMIT :limit OFFSET :offset")
            .param("limit", page.size)
            .param("offset", page.offset)
            .query(mapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM users").query(Long::class.javaObjectType).single()
        return Page.of(items, page, total)
    }

    override fun findAdminIds(): List<UUID> =
        jdbc.sql("SELECT id FROM users WHERE role = 'ADMIN' AND NOT blocked")
            .query(RowMapper { rs, _ -> rs.uuid("id") })
            .list()

    override fun findDisplayNames(ids: Collection<UUID>): Map<UUID, String> =
        jdbc.sql("SELECT id, display_name FROM users WHERE id IN (:ids)")
            .param("ids", ids.toList())
            .query(RowMapper { rs, _ -> rs.uuid("id") to rs.getString("display_name") })
            .list()
            .toMap()
}
