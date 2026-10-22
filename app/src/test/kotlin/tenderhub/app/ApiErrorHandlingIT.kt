package tenderhub.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
class ApiErrorHandlingIT : IntegrationTestBase() {
    @Test
    fun `registration and duplicate e-mail`() {
        val email = "reg-${UUID.randomUUID()}@test.local"
        val body = """{"email": "$email", "password": "password1", "displayName": "Reg"}"""
        val created = expect(201, post("/api/v1/auth/register", null, body))
        assertEquals("USER", created.path("role").asText())
        expectError(409, "EMAIL_TAKEN", post("/api/v1/auth/register", null, body))
        expectError(400, "WEAK_PASSWORD", post("/api/v1/auth/register", null, """{"email": "x-$email", "password": "123", "displayName": "R"}"""))
    }

    @Test
    fun `authentication and authorization`() {
        expectError(401, "UNAUTHORIZED", get("/api/v1/tenders", null))
        val wrongPassword = newUser().copy(password = "wrong-password1")
        expectError(401, "UNAUTHORIZED", get("/api/v1/tenders", wrongPassword))
        expectError(403, "ACCESS_DENIED", get("/api/v1/admin/tenders/review-queue", newUser()))
    }

    @Test
    fun `malformed and invalid input gives 400 with details`() {
        val user = newUser()
        expectError(400, "MALFORMED_REQUEST", post("/api/v1/tenders", user, "{not json"))

        val missingField = expect(400, post("/api/v1/tenders", user, """{"title": "Без остальных полей"}"""))
        assertEquals("MALFORMED_REQUEST", missingField.path("code").asText())

        val blankTitle = expect(400, post("/api/v1/tenders", user, tenderJson(title = " ")))
        assertEquals("VALIDATION_FAILED", blankTitle.path("code").asText())
        assertTrue(blankTitle.path("errors").any { it.path("field").asText() == "title" })

        expectError(400, "MALFORMED_REQUEST", post("/api/v1/tenders", user, tenderJson().replace("SUPPLIES", "SPACESHIPS")))
        expect(400, get("/api/v1/tenders/not-a-uuid", user))
        expect(400, get("/api/v1/tenders?size=1000", user))
    }

    @Test
    fun `business rule violations give 422`() {
        val user = newUser()
        expectError(422, "WEIGHTS_SUM_NOT_100", post("/api/v1/tenders", user, tenderJson(weights = Triple(50, 30, 10))))
        expectError(422, "DEADLINE_TOO_EARLY", post("/api/v1/tenders", user, tenderJson(deadline = now().plus(Duration.ofHours(1)))))
        expectError(422, "DEADLINE_TOO_LATE", post("/api/v1/tenders", user, tenderJson(deadline = now().plus(Duration.ofDays(120)))))
    }

    @Test
    fun `unknown resources give 404`() {
        val user = newUser()
        expectError(404, "TENDER_NOT_FOUND", get("/api/v1/tenders/${UUID.randomUUID()}", user))
        expectError(404, "BID_NOT_FOUND", get("/api/v1/tenders/${openTender(newUser())}/bids/my", user))
    }

    @Test
    fun `state conflicts give 409`() {
        val owner = newUser()
        val tenderId = openTender(owner)
        expectError(409, "TENDER_NOT_ON_REVIEW", post("/api/v1/admin/tenders/$tenderId/approve", admin))
        expectError(409, "TENDER_NOT_EDITABLE", put("/api/v1/tenders/$tenderId", owner, tenderJson()))
    }
}
