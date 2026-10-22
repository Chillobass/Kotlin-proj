package tenderhub.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tenderhub.common.Actor
import tenderhub.common.MutableClock
import tenderhub.users.application.RegisterUserCommand
import tenderhub.users.application.UserService
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>(DockerImageName.parse("postgres:16-alpine"))

    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
}

@SpringBootTest(
    properties = [
        "tenderhub.closing.enabled=false",
        "tenderhub.admin.email=admin@test.local",
        "tenderhub.admin.password=adminpass1",
    ],
)
@AutoConfigureMockMvc
@Import(TestInfrastructure::class)
abstract class IntegrationTestBase {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var userService: UserService

    data class TestUser(val id: UUID, val email: String, val password: String) {
        fun actor(admin: Boolean = false) =
            Actor(id, if (admin) tenderhub.common.Role.ADMIN else tenderhub.common.Role.USER, blocked = false)
    }

    val admin: TestUser by lazy {
        val user = userService.findForAuthentication(ADMIN_EMAIL)!!
        TestUser(user.id, ADMIN_EMAIL, ADMIN_PASSWORD)
    }

    fun newUser(name: String = "user"): TestUser {
        val email = "$name-${UUID.randomUUID()}@test.local"
        val password = "password1"
        val user = userService.register(RegisterUserCommand(email, password, name))
        return TestUser(user.id, email, password)
    }

    fun now(): Instant = clock.instant()

    fun tenderJson(
        deadline: Instant = now().plus(Duration.ofDays(2)),
        maxPrice: String = "1000000",
        weights: Triple<Int, Int, Int> = Triple(60, 30, 10),
        title: String = "Поставка ноутбуков",
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "title" to title,
            "description" to "500 ноутбуков для офиса",
            "category" to "SUPPLIES",
            "maxPrice" to maxPrice,
            "deadline" to deadline.toString(),
            "weights" to mapOf("price" to weights.first, "delivery" to weights.second, "warranty" to weights.third),
        ),
    )

    fun bidJson(price: String, deliveryDays: Int, warrantyMonths: Int): String = objectMapper.writeValueAsString(
        mapOf("price" to price, "deliveryDays" to deliveryDays, "warrantyMonths" to warrantyMonths, "comment" to "test"),
    )

    fun call(builder: MockHttpServletRequestBuilder, user: TestUser?, body: String? = null): Pair<Int, JsonNode> {
        var request = builder.accept(MediaType.APPLICATION_JSON)
        if (user != null) request = request.with(httpBasic(user.email, user.password))
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body)
        val response = mockMvc.perform(request).andReturn().response
        response.characterEncoding = "UTF-8"
        val text = response.contentAsString
        val json = if (text.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(text)
        return response.status to json
    }

    fun get(path: String, user: TestUser?) = call(MockMvcRequestBuilders.get(path), user)

    fun post(path: String, user: TestUser?, body: String? = null) = call(MockMvcRequestBuilders.post(path), user, body)

    fun put(path: String, user: TestUser?, body: String) = call(MockMvcRequestBuilders.put(path), user, body)

    fun expect(status: Int, result: Pair<Int, JsonNode>): JsonNode {
        assertEquals(status, result.first, "Unexpected status, body: ${result.second}")
        return result.second
    }

    fun expectError(status: Int, code: String, result: Pair<Int, JsonNode>) {
        val body = expect(status, result)
        assertEquals(code, body.path("code").asText(), "Unexpected error code, body: $body")
    }

    fun openTender(owner: TestUser, deadline: Instant = now().plus(Duration.ofDays(2))): String {
        val id = expect(201, post("/api/v1/tenders", owner, tenderJson(deadline))).path("id").asText()
        expect(200, post("/api/v1/admin/tenders/$id/approve", admin))
        return id
    }

    companion object {
        const val ADMIN_EMAIL = "admin@test.local"
        const val ADMIN_PASSWORD = "adminpass1"
    }
}
