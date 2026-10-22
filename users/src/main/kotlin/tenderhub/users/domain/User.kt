package tenderhub.users.domain

import tenderhub.common.Actor
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.Role
import tenderhub.common.validate
import java.time.Instant
import java.util.UUID

class User(
    val id: UUID,
    val email: String,
    val displayName: String,
    val passwordHash: String,
    val role: Role,
    blocked: Boolean,
    blockReason: String?,
    val createdAt: Instant,
) {
    var blocked: Boolean = blocked
        private set

    var blockReason: String? = blockReason
        private set

    fun block(reason: String) {
        if (role == Role.ADMIN) throw BusinessRuleException("CANNOT_BLOCK_ADMIN", "Нельзя заблокировать администратора")
        if (blocked) throw ConflictException("USER_ALREADY_BLOCKED", "Пользователь уже заблокирован")
        val trimmed = reason.trim()
        validate(trimmed.isNotEmpty(), "reason", "REASON_REQUIRED") { "Укажите причину блокировки" }
        validate(trimmed.length <= MAX_REASON_LENGTH, "reason", "REASON_TOO_LONG") {
            "Причина не длиннее $MAX_REASON_LENGTH символов"
        }
        blocked = true
        blockReason = trimmed
    }

    fun unblock() {
        if (!blocked) throw ConflictException("USER_NOT_BLOCKED", "Пользователь не заблокирован")
        blocked = false
        blockReason = null
    }

    fun toActor(): Actor = Actor(id, role, blocked)

    companion object {
        const val MAX_REASON_LENGTH = 1000

        fun register(
            id: UUID,
            email: Email,
            displayName: String,
            passwordHash: String,
            role: Role,
            now: Instant,
        ): User {
            val name = displayName.trim()
            validate(name.length in 1..100, "displayName", "INVALID_DISPLAY_NAME") {
                "Имя должно содержать от 1 до 100 символов"
            }
            return User(id, email.value, name, passwordHash, role, blocked = false, blockReason = null, createdAt = now)
        }
    }
}

@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun of(raw: String): Email {
            val normalized = raw.trim().lowercase()
            validate(normalized.length <= 254 && PATTERN.matches(normalized), "email", "INVALID_EMAIL") {
                "Некорректный e-mail"
            }
            return Email(normalized)
        }
    }
}

object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 72 // BCrypt limit

    fun check(raw: String) {
        validate(raw.length in MIN_LENGTH..MAX_LENGTH, "password", "WEAK_PASSWORD") {
            "Пароль должен содержать от $MIN_LENGTH до $MAX_LENGTH символов"
        }
        validate(raw.any { it.isLetter() } && raw.any { it.isDigit() }, "password", "WEAK_PASSWORD") {
            "Пароль должен содержать буквы и цифры"
        }
    }
}
