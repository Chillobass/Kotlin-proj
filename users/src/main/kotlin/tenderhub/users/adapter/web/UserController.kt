package tenderhub.users.adapter.web

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tenderhub.common.Actor
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.users.application.RegisterUserCommand
import tenderhub.users.application.UserService
import tenderhub.users.domain.User
import java.time.Instant
import java.util.UUID

data class RegisterRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
    @field:NotBlank @field:Size(max = 100) val displayName: String,
)

data class BlockRequest(
    @field:NotBlank @field:Size(max = 1000) val reason: String,
)

data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: String,
    val blocked: Boolean,
    val blockReason: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id,
            email = user.email,
            displayName = user.displayName,
            role = user.role.name,
            blocked = user.blocked,
            blockReason = user.blockReason,
            createdAt = user.createdAt,
        )
    }
}

@RestController
@RequestMapping("/api/v1")
class UserController(private val userService: UserService) {
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterRequest): UserResponse =
        UserResponse.from(userService.register(RegisterUserCommand(request.email, request.password, request.displayName)))

    @GetMapping("/users/me")
    fun me(actor: Actor): UserResponse = UserResponse.from(userService.me(actor))

    @GetMapping("/admin/users")
    fun list(
        actor: Actor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<UserResponse> = userService.list(actor, PageRequest(page, size)).map(UserResponse::from)

    @PostMapping("/admin/users/{id}/block")
    fun block(actor: Actor, @PathVariable id: UUID, @Valid @RequestBody request: BlockRequest): UserResponse =
        UserResponse.from(userService.block(actor, id, request.reason))

    @PostMapping("/admin/users/{id}/unblock")
    fun unblock(actor: Actor, @PathVariable id: UUID): UserResponse =
        UserResponse.from(userService.unblock(actor, id))
}
