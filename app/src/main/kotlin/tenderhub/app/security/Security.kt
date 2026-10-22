package tenderhub.app.security

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tenderhub.common.Actor
import tenderhub.common.Role
import tenderhub.users.application.UserService
import java.net.URI
import java.util.UUID

class AuthenticatedUser(
    val id: UUID,
    private val email: String,
    private val passwordHash: String,
    val role: Role,
    val blocked: Boolean,
) : UserDetails {
    fun toActor() = Actor(id, role, blocked)

    override fun getAuthorities(): MutableCollection<out GrantedAuthority> =
        mutableListOf(SimpleGrantedAuthority("ROLE_${role.name}"))

    override fun getPassword(): String = passwordHash

    override fun getUsername(): String = email

    override fun isAccountNonExpired() = true

    override fun isAccountNonLocked() = true

    override fun isCredentialsNonExpired() = true

    override fun isEnabled() = true
}

@Component
class DatabaseUserDetailsService(private val userService: UserService) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        val user = userService.findForAuthentication(username) ?: throw UsernameNotFoundException("Unknown user")
        return AuthenticatedUser(user.id, user.email, user.passwordHash, user.role, user.blocked)
    }
}

/**
 * Resolves controller parameters of type [Actor] from the security context.
 * Thanks to this, controllers and business code never depend on Spring Security.
 */
class ActorArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == Actor::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
            ?: throw org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                "Authentication required",
            )
        return principal.toActor()
    }
}

@Configuration(proxyBeanMethods = false)
class ActorResolverConfig : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(ActorArgumentResolver())
    }
}

@Component
class SecurityProblemWriter(private val objectMapper: ObjectMapper) : AuthenticationEntryPoint, AccessDeniedHandler {
    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        response.setHeader("WWW-Authenticate", "Basic realm=\"tenderhub\"")
        write(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Требуется аутентификация (HTTP Basic)")
    }

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        write(response, request, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Недостаточно прав")
    }

    private fun write(response: HttpServletResponse, request: HttpServletRequest, status: HttpStatus, code: String, detail: String) {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            instance = URI.create(request.requestURI)
            setProperty("code", code)
        }
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, problem)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity, problemWriter: SecurityProblemWriter): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.authenticationEntryPoint(problemWriter) }
            .exceptionHandling {
                it.authenticationEntryPoint(problemWriter)
                it.accessDeniedHandler(problemWriter)
            }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                it.requestMatchers("/actuator/health/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/error")
                    .permitAll()
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
        return http.build()
    }
}
