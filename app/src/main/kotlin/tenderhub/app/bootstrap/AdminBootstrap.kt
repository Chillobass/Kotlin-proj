package tenderhub.app.bootstrap

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tenderhub.users.application.UserService

@ConfigurationProperties("tenderhub.admin")
data class AdminProperties(
    val email: String = "",
    val password: String = "",
    val displayName: String = "Administrator",
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties::class)
class AdminBootstrapConfig

@Component
class AdminBootstrap(
    private val properties: AdminProperties,
    private val userService: UserService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (properties.email.isBlank() || properties.password.isBlank()) {
            log.warn("tenderhub.admin.email/password are not set: no bootstrap administrator will be created")
            return
        }
        val admin = userService.ensureAdmin(properties.email, properties.password, properties.displayName)
        log.info("Bootstrap administrator is available: {}", admin.email)
    }
}
