package tenderhub.tenders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tenderhub.common.Actors
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.ValidationException
import tenderhub.tenders.domain.CriteriaWeights
import tenderhub.tenders.domain.Tender
import tenderhub.tenders.domain.TenderCategory
import tenderhub.tenders.domain.TenderPolicy
import tenderhub.tenders.domain.TenderStatus
import tenderhub.tenders.domain.TenderTerms
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class TenderTest {
    private val now = Instant.parse("2026-01-10T10:00:00Z")
    private val policy = TenderPolicy()
    private val ownerId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()

    private fun terms(deadline: Instant = now.plus(Duration.ofDays(7))) = TenderTerms(
        title = "Поставка ноутбуков",
        description = "500 ноутбуков для офиса",
        category = TenderCategory.SUPPLIES,
        maxPrice = BigDecimal("1000000.00"),
        deadline = deadline,
        weights = CriteriaWeights(60, 30, 10),
    )

    private fun submitted(deadline: Instant = now.plus(Duration.ofDays(7))) =
        Tender.submit(UUID.randomUUID(), ownerId, terms(deadline), policy, now)

    private fun open(): Tender = submitted().also { it.approve(adminId, policy, now) }

    @Test
    fun `new tender goes to review and is hidden from other users`() {
        val tender = submitted()
        assertEquals(TenderStatus.ON_REVIEW, tender.status)
        assertFalse(tender.isPublished)
        assertFalse(tender.isVisibleTo(Actors.user()))
        assertTrue(tender.isVisibleTo(Actors.user(ownerId)))
        assertTrue(tender.isVisibleTo(Actors.admin()))
    }

    @Test
    fun `weights must sum to 100`() {
        val ex = assertThrows<BusinessRuleException> { CriteriaWeights(50, 30, 10) }
        assertEquals("WEIGHTS_SUM_NOT_100", ex.code)
        assertThrows<ValidationException> { CriteriaWeights(110, -10, 0) }
    }

    @Test
    fun `deadline must be between 24 hours and 90 days from now`() {
        assertEquals(
            "DEADLINE_TOO_EARLY",
            assertThrows<BusinessRuleException> { submitted(now.plus(Duration.ofHours(23))) }.code,
        )
        assertEquals(
            "DEADLINE_TOO_LATE",
            assertThrows<BusinessRuleException> { submitted(now.plus(Duration.ofDays(91))) }.code,
        )
        submitted(now.plus(Duration.ofHours(24)))
    }

    @Test
    fun `invalid terms are rejected`() {
        assertThrows<ValidationException> { terms().copy(title = "  ") }
        assertThrows<ValidationException> { terms().copy(maxPrice = BigDecimal.ZERO) }
        assertThrows<ValidationException> { terms().copy(maxPrice = BigDecimal("10.001")) }
    }

    @Test
    fun `approval publishes the tender`() {
        val tender = open()
        assertEquals(TenderStatus.OPEN, tender.status)
        assertEquals(now, tender.publishedAt)
        assertEquals(adminId, tender.moderatedBy)
        assertTrue(tender.isVisibleTo(Actors.user()))
    }

    @Test
    fun `cannot approve when less than min open duration remains`() {
        val tender = submitted(now.plus(Duration.ofHours(30)))
        val later = now.plus(Duration.ofHours(10))
        val ex = assertThrows<BusinessRuleException> { tender.approve(adminId, policy, later) }
        assertEquals("DEADLINE_TOO_CLOSE", ex.code)
        assertEquals(TenderStatus.ON_REVIEW, tender.status)
    }

    @Test
    fun `reject requires a reason and allows edit and resubmit`() {
        val tender = submitted()
        assertThrows<ValidationException> { tender.reject(adminId, "  ", now) }

        tender.reject(adminId, "Нет требований к исполнителю", now)
        assertEquals(TenderStatus.REJECTED, tender.status)
        assertEquals("Нет требований к исполнителю", tender.statusReason)

        tender.edit(terms().copy(description = "500 ноутбуков, гарантия от 12 мес."), policy, now)
        tender.resubmit(policy, now)
        assertEquals(TenderStatus.ON_REVIEW, tender.status)
        assertNull(tender.statusReason)
    }

    @Test
    fun `published terms are frozen`() {
        val tender = open()
        val ex = assertThrows<ConflictException> { tender.edit(terms(), policy, now) }
        assertEquals("TENDER_NOT_EDITABLE", ex.code)
    }

    @Test
    fun `owner can cancel an open tender only while there are no bids`() {
        val withBids = open()
        val ex = assertThrows<BusinessRuleException> { withBids.cancelByOwner(null, hasActiveBids = true, now = now) }
        assertEquals("TENDER_HAS_BIDS", ex.code)

        val withoutBids = open()
        withoutBids.cancelByOwner(null, hasActiveBids = false, now = now)
        assertEquals(TenderStatus.CANCELLED, withoutBids.status)
    }

    @Test
    fun `admin can cancel an open tender with bids but must give a reason`() {
        val tender = open()
        assertThrows<ValidationException> { tender.cancelByAdmin("", now) }
        tender.cancelByAdmin("Мошенничество", now)
        assertEquals(TenderStatus.CANCELLED, tender.status)
        assertEquals("Мошенничество", tender.statusReason)
    }

    @Test
    fun `close before deadline is not allowed`() {
        val tender = open()
        assertThrows<ConflictException> { tender.close(hasBids = true, now = now) }
    }

    @Test
    fun `close after deadline completes or fails depending on bids`() {
        val afterDeadline = now.plus(Duration.ofDays(8))

        val completed = open().also { it.close(hasBids = true, now = afterDeadline) }
        assertEquals(TenderStatus.COMPLETED, completed.status)
        assertNotNull(completed.closedAt)

        val failed = open().also { it.close(hasBids = false, now = afterDeadline) }
        assertEquals(TenderStatus.FAILED, failed.status)
    }

    @Test
    fun `closed tender cannot be cancelled`() {
        val tender = open().also { it.close(hasBids = true, now = now.plus(Duration.ofDays(8))) }
        assertThrows<ConflictException> { tender.cancelByAdmin("too late", now.plus(Duration.ofDays(9))) }
    }
}
