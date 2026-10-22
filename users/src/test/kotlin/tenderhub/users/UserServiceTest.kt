package tenderhub.users

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tenderhub.common.Actors
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.ForbiddenException
import tenderhub.common.InPlaceTransactionRunner
import tenderhub.common.MutableClock
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.RecordingEventPublisher
import tenderhub.common.Role
import tenderhub.common.ValidationException
import tenderhub.users.api.UserBlocked
import tenderhub.users.application.PasswordHasher
import tenderhub.users.application.RegisterUserCommand
import tenderhub.users.application.UserRepository
import tenderhub.users.application.UserService
import tenderhub.users.domain.User
import java.util.UUID

class UserServiceTest {
    private val repo = InMemoryUserRepository()
    private val events = RecordingEventPublisher()
    private val hasher = object : PasswordHasher {
        override fun hash(raw: String) = "hashed:$raw"

        override fun matches(raw: String, hash: String) = hash == "hashed:$raw"
    }
    private val service = UserService(repo, hasher, InPlaceTransactionRunner, events, MutableClock())

    @Test
    fun `registration normalizes e-mail and hashes password`() {
        val user = service.register(RegisterUserCommand("  Ivan@Example.COM ", "secret123", "Иван"))
        assertEquals("ivan@example.com", user.email)
        assertEquals(Role.USER, user.role)
        assertEquals("hashed:secret123", user.passwordHash)
    }

    @Test
    fun `duplicate e-mail is a conflict`() {
        service.register(RegisterUserCommand("a@b.ru", "secret123", "A"))
        assertThrows<ConflictException> { service.register(RegisterUserCommand("A@B.ru", "secret123", "B")) }
    }

    @Test
    fun `weak password and bad e-mail are rejected`() {
        assertThrows<ValidationException> { service.register(RegisterUserCommand("a@b.ru", "short1", "A")) }
        assertThrows<ValidationException> { service.register(RegisterUserCommand("a@b.ru", "onlyletters", "A")) }
        assertThrows<ValidationException> { service.register(RegisterUserCommand("not-an-email", "secret123", "A")) }
    }

    @Test
    fun `admin blocks a user and the event is published`() {
        val user = service.register(RegisterUserCommand("u@b.ru", "secret123", "U"))
        val admin = Actors.admin()

        val blocked = service.block(admin, user.id, "спам")

        assertTrue(blocked.blocked)
        assertEquals(user.id, events.ofType<UserBlocked>().single().userId)
        assertThrows<ConflictException> { service.block(admin, user.id, "ещё раз") }
        assertEquals(false, service.unblock(admin, user.id).blocked)
    }

    @Test
    fun `only admins block, admins cannot be blocked`() {
        val user = service.register(RegisterUserCommand("u@b.ru", "secret123", "U"))
        assertThrows<ForbiddenException> { service.block(Actors.user(), user.id, "x") }

        val admin = service.ensureAdmin("root@b.ru", "rootpass1", "Root")
        assertThrows<BusinessRuleException> { service.block(Actors.admin(), admin.id, "x") }
    }

    @Test
    fun `ensureAdmin is idempotent`() {
        val first = service.ensureAdmin("root@b.ru", "rootpass1", "Root")
        val second = service.ensureAdmin("root@b.ru", "rootpass1", "Root")
        assertEquals(first.id, second.id)
        assertEquals(listOf(first.id), service.adminIds())
    }
}

class InMemoryUserRepository : UserRepository {
    private val rows = linkedMapOf<UUID, User>()

    override fun insert(user: User) {
        if (existsByEmail(user.email)) throw ConflictException("EMAIL_TAKEN", "taken")
        rows[user.id] = user
    }

    override fun update(user: User) {
        rows[user.id] = user
    }

    override fun findById(id: UUID) = rows[id]

    override fun findByIdForUpdate(id: UUID) = rows[id]

    override fun findByEmail(email: String) = rows.values.firstOrNull { it.email == email }

    override fun existsByEmail(email: String) = rows.values.any { it.email == email }

    override fun findAll(page: PageRequest) = Page.of(rows.values.toList(), page, rows.size.toLong())

    override fun findAdminIds() = rows.values.filter { it.role == Role.ADMIN && !it.blocked }.map { it.id }

    override fun findDisplayNames(ids: Collection<UUID>) =
        rows.values.filter { it.id in ids }.associate { it.id to it.displayName }
}
