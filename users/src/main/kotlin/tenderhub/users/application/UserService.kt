package tenderhub.users.application

import org.slf4j.LoggerFactory
import tenderhub.common.Actor
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.EventPublisher
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.Role
import tenderhub.common.TransactionRunner
import tenderhub.users.api.UserBlocked
import tenderhub.users.api.UserDirectory
import tenderhub.users.domain.Email
import tenderhub.users.domain.PasswordPolicy
import tenderhub.users.domain.User
import java.time.Clock
import java.util.UUID

data class RegisterUserCommand(
    val email: String,
    val password: String,
    val displayName: String,
)

class UserService(
    private val users: UserRepository,
    private val passwordHasher: PasswordHasher,
    private val tx: TransactionRunner,
    private val events: EventPublisher,
    private val clock: Clock,
) : UserDirectory {
    private val log = LoggerFactory.getLogger(javaClass)

    fun register(command: RegisterUserCommand): User {
        val email = Email.of(command.email)
        PasswordPolicy.check(command.password)
        return tx.inTransaction {
            if (users.existsByEmail(email.value)) {
                throw ConflictException("EMAIL_TAKEN", "Пользователь с таким e-mail уже зарегистрирован")
            }
            val user = User.register(
                id = UUID.randomUUID(),
                email = email,
                displayName = command.displayName,
                passwordHash = passwordHasher.hash(command.password),
                role = Role.USER,
                now = clock.instant(),
            )
            users.insert(user)
            log.info("User registered: id={}", user.id)
            user
        }
    }

    fun ensureAdmin(email: String, password: String, displayName: String): User = tx.inTransaction {
        val normalized = Email.of(email)
        val existing = users.findByEmail(normalized.value)
        if (existing != null) {
            if (existing.role != Role.ADMIN) {
                throw BusinessRuleException(
                    "ADMIN_EMAIL_TAKEN",
                    "E-mail администратора уже занят обычным пользователем",
                )
            }
            existing
        } else {
            PasswordPolicy.check(password)
            val admin = User.register(
                id = UUID.randomUUID(),
                email = normalized,
                displayName = displayName,
                passwordHash = passwordHasher.hash(password),
                role = Role.ADMIN,
                now = clock.instant(),
            )
            users.insert(admin)
            log.info("Bootstrap administrator created: id={}", admin.id)
            admin
        }
    }

    fun findForAuthentication(email: String): User? = tx.readOnly { users.findByEmail(email.trim().lowercase()) }

    fun me(actor: Actor): User = tx.readOnly { users.findById(actor.userId) ?: throw userNotFound(actor.userId) }

    fun list(actor: Actor, page: PageRequest): Page<User> {
        actor.requireAdmin()
        return tx.readOnly { users.findAll(page) }
    }

    fun block(actor: Actor, userId: UUID, reason: String): User {
        actor.requireAdmin()
        if (actor.userId == userId) throw BusinessRuleException("CANNOT_BLOCK_SELF", "Нельзя заблокировать самого себя")
        return tx.inTransaction {
            val user = users.findByIdForUpdate(userId) ?: throw userNotFound(userId)
            user.block(reason)
            users.update(user)
            events.publish(UserBlocked(user.id, user.blockReason!!, clock.instant()))
            log.info("User blocked: id={}, by admin={}", user.id, actor.userId)
            user
        }
    }

    fun unblock(actor: Actor, userId: UUID): User {
        actor.requireAdmin()
        return tx.inTransaction {
            val user = users.findByIdForUpdate(userId) ?: throw userNotFound(userId)
            user.unblock()
            users.update(user)
            log.info("User unblocked: id={}, by admin={}", user.id, actor.userId)
            user
        }
    }

    override fun adminIds(): List<UUID> = tx.readOnly { users.findAdminIds() }

    override fun displayNames(ids: Collection<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap() else tx.readOnly { users.findDisplayNames(ids) }

    private fun userNotFound(id: UUID) = NotFoundException("USER_NOT_FOUND", "Пользователь $id не найден")
}
