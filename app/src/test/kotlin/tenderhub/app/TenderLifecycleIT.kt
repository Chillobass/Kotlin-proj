package tenderhub.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.junit.jupiter.Testcontainers
import tenderhub.bidding.application.TenderClosingService
import java.time.Duration

@Testcontainers(disabledWithoutDocker = true)
class TenderLifecycleIT : IntegrationTestBase() {
    @Autowired
    lateinit var closingService: TenderClosingService

    @Test
    fun `full tender lifecycle`() {
        val owner = newUser("owner")
        val alice = newUser("alice")
        val bob = newUser("bob")
        val carol = newUser("carol")

        val created = expect(201, post("/api/v1/tenders", owner, tenderJson()))
        val tenderId = created.path("id").asText()
        assertEquals("ON_REVIEW", created.path("status").asText())
        expectError(404, "TENDER_NOT_FOUND", get("/api/v1/tenders/$tenderId", alice))
        expectError(404, "TENDER_NOT_FOUND", post("/api/v1/tenders/$tenderId/bids", alice, bidJson("900000", 30, 12)))

        val queue = expect(200, get("/api/v1/admin/tenders/review-queue?size=100", admin))
        assertTrue(queue.path("items").any { it.path("id").asText() == tenderId })

        assertEquals("OPEN", expect(200, post("/api/v1/admin/tenders/$tenderId/approve", admin)).path("status").asText())
        val ownerNotifications = expect(200, get("/api/v1/notifications", owner)).path("items")
        assertTrue(ownerNotifications.any { it.path("type").asText() == "TENDER_APPROVED" })

        val aliceBid = expect(201, post("/api/v1/tenders/$tenderId/bids", alice, bidJson("900000", 30, 12)))
        expect(201, post("/api/v1/tenders/$tenderId/bids", bob, bidJson("1000000", 20, 6)))
        expect(201, post("/api/v1/tenders/$tenderId/bids", carol, bidJson("950000", 40, 12)))

        expectError(409, "BID_ALREADY_EXISTS", post("/api/v1/tenders/$tenderId/bids", alice, bidJson("800000", 30, 12)))
        expectError(403, "OWN_TENDER", post("/api/v1/tenders/$tenderId/bids", owner, bidJson("800000", 30, 12)))
        expectError(422, "PRICE_ABOVE_MAX", post("/api/v1/tenders/$tenderId/bids", newUser(), bidJson("1000001", 30, 12)))
        expectError(403, "ADMIN_CANNOT_PARTICIPATE", post("/api/v1/tenders/$tenderId/bids", admin, bidJson("1", 1, 1)))

        expect(200, post("/api/v1/admin/users/${carol.id}/block", admin, """{"reason": "Спам"}"""))

        assertEquals(2, expect(200, get("/api/v1/tenders/$tenderId", bob)).path("activeBids").asInt())
        expectError(409, "RESULTS_NOT_AVAILABLE", get("/api/v1/tenders/$tenderId/results", owner))
        expectError(409, "RESULTS_NOT_AVAILABLE", get("/api/v1/tenders/$tenderId/results", admin))
        assertEquals(aliceBid.path("id").asText(), expect(200, get("/api/v1/tenders/$tenderId/bids/my", alice)).path("id").asText())

        expectError(422, "TENDER_HAS_BIDS", post("/api/v1/tenders/$tenderId/cancel", owner))

        clock.advance(Duration.ofDays(2).plusMinutes(1))
        expectError(409, "TENDER_DEADLINE_PASSED", post("/api/v1/tenders/$tenderId/bids", newUser(), bidJson("1", 1, 1)))

        assertTrue(closingService.closeExpired() >= 1)

        val results = expect(200, get("/api/v1/tenders/$tenderId/results", carol))
        assertEquals("COMPLETED", results.path("status").asText())
        val rating = results.path("rating")
        assertEquals(2, rating.size())
        assertEquals(alice.id.toString(), rating[0].path("participantId").asText())
        assertEquals("90.0", rating[0].path("score").asText())
        assertEquals(bob.id.toString(), rating[1].path("participantId").asText())
        assertEquals("89.0", rating[1].path("score").asText())
        assertEquals(alice.id.toString(), results.path("winner").path("participantId").asText())

        val aliceNotifications = expect(200, get("/api/v1/notifications", alice)).path("items")
        assertTrue(aliceNotifications.any { it.path("type").asText() == "BID_RESULT" && it.path("message").asText().contains("победила") })
        val myBids = expect(200, get("/api/v1/bids/my", bob)).path("items")
        assertEquals(2, myBids[0].path("place").asInt())

        val completed = expect(200, get("/api/v1/tenders?status=COMPLETED&size=100", carol)).path("items")
        assertTrue(completed.any { it.path("id").asText() == tenderId && it.path("resultsAvailable").asBoolean() })
    }

    @Test
    fun `tender without bids fails`() {
        val owner = newUser("owner")
        val tenderId = openTender(owner)
        clock.advance(Duration.ofDays(3))
        closingService.closeExpired()

        val results = expect(200, get("/api/v1/tenders/$tenderId/results", owner))
        assertEquals("FAILED", results.path("status").asText())
        assertEquals(0, results.path("rating").size())
        assertTrue(results.path("winner").isNull)
    }

    @Test
    fun `rejected tender can be edited and resubmitted`() {
        val owner = newUser("owner")
        val tenderId = expect(201, post("/api/v1/tenders", owner, tenderJson())).path("id").asText()

        expectError(400, "VALIDATION_FAILED", post("/api/v1/admin/tenders/$tenderId/reject", admin, """{"reason": ""}"""))
        val rejected = expect(200, post("/api/v1/admin/tenders/$tenderId/reject", admin, """{"reason": "Уточните требования"}"""))
        assertEquals("REJECTED", rejected.path("status").asText())
        assertEquals("Уточните требования", rejected.path("statusReason").asText())

        expect(200, put("/api/v1/tenders/$tenderId", owner, tenderJson(title = "Поставка 500 ноутбуков, гарантия 12 мес.")))
        assertEquals("ON_REVIEW", expect(200, post("/api/v1/tenders/$tenderId/resubmit", owner)).path("status").asText())
    }

    @Test
    fun `blocking a user cancels his tenders and notifies participants`() {
        val owner = newUser("owner")
        val alice = newUser("alice")
        val tenderId = openTender(owner)
        expect(201, post("/api/v1/tenders/$tenderId/bids", alice, bidJson("500000", 10, 0)))

        expect(200, post("/api/v1/admin/users/${owner.id}/block", admin, """{"reason": "Мошенничество"}"""))

        assertEquals("CANCELLED", expect(200, get("/api/v1/tenders/$tenderId", alice)).path("status").asText())
        val notifications = expect(200, get("/api/v1/notifications", alice)).path("items")
        assertTrue(notifications.any { it.path("type").asText() == "TENDER_CANCELLED" })

        expect(200, get("/api/v1/tenders", owner))
        expectError(403, "USER_BLOCKED", post("/api/v1/tenders", owner, tenderJson()))
    }
}
