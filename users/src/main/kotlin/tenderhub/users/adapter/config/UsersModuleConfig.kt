package tenderhub.users.adapter.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import tenderhub.common.EventPublisher
import tenderhub.common.TransactionRunner
import tenderhub.users.adapter.security.BCryptPasswordHasher
import tenderhub.users.application.PasswordHasher
import tenderhub.users.application.UserRepository
import tenderhub.users.application.UserService
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class UsersModuleConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun passwordHasher(encoder: PasswordEncoder): PasswordHasher = BCryptPasswordHasher(encoder)

    @Bean
    fun userService(
        users: UserRepository,
        passwordHasher: PasswordHasher,
        tx: TransactionRunner,
        events: EventPublisher,
        clock: Clock,
    ): UserService = UserService(users, passwordHasher, tx, events, clock)
}
