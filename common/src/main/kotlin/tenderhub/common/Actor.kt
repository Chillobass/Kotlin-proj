package tenderhub.common

import java.util.UUID

enum class Role { USER, ADMIN }

data class Actor(
    val userId: UUID,
    val role: Role,
    val blocked: Boolean,
) {
    val isAdmin: Boolean get() = role == Role.ADMIN

    fun requireAdmin() {
        if (!isAdmin) throw ForbiddenException("ADMIN_ONLY", "Действие доступно только администратору")
    }

    fun requireActiveUser() {
        if (isAdmin) {
            throw ForbiddenException(
                "ADMIN_CANNOT_PARTICIPATE",
                "Администратор не может создавать тендеры и участвовать в них",
            )
        }
        if (blocked) throw ForbiddenException("USER_BLOCKED", "Пользователь заблокирован")
    }
}
