package tenderhub.tenders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tenderhub.common.Actors
import tenderhub.common.BusinessRuleException
import tenderhub.common.ForbiddenException
import tenderhub.common.InPlaceTransactionRunner
import tenderhub.common.MutableClock
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.RecordingEventPublisher
import tenderhub.common.ValidationException
import tenderhub.tenders.api.BidStatistics
import tenderhub.tenders.api.TenderApproved
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderSubmitted
import tenderhub.tenders.application.ModerationService
import tenderhub.tenders.application.TenderCatalogService
import tenderhub.tenders.application.TenderRepository
import tenderhub.tenders.application.TenderSearchFilter
import tenderhub.tenders.application.TenderService
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

class TenderServiceTest {
    private val clock = MutableClock()
    private val repo = InMemoryTenderRepository()
    private val bids = FakeBidStatistics()
    private val events = RecordingEventPublisher()
    private val policy = TenderPolicy()
    private val tenders = TenderService(repo, bids, policy, InPlaceTransactionRunner, events, clock)
    private val moderation = ModerationService(repo, bids, policy, InPlaceTransactionRunner, events, clock)
    private val catalog = TenderCatalogService(repo, InPlaceTransactionRunner)

    private val owner = Actors.user()
    private val admin = Actors.admin()

    private fun terms() = TenderTerms(
        title = "Ремонт офиса",
        description = "Косметический ремонт 200 м²",
        category = TenderCategory.CONSTRUCTION,
        maxPrice = BigDecimal("500000"),
        deadline = clock.instant().plus(Duration.ofDays(5)),
        weights = CriteriaWeights(50, 30, 20),
    )

    @Test
    fun `create publishes event for moderators`() {
        val view = tenders.create(owner, terms())
        assertEquals(TenderStatus.ON_REVIEW, view.tender.status)
        assertEquals(view.tender.id, events.ofType<TenderSubmitted>().single().tenderId)
    }

    @Test
    fun `admins and blocked users cannot create tenders`() {
        assertEquals("ADMIN_CANNOT_PARTICIPATE", assertThrows<ForbiddenException> { tenders.create(admin, terms()) }.code)
        assertEquals("USER_BLOCKED", assertThrows<ForbiddenException> { tenders.create(Actors.blockedUser(), terms()) }.code)
    }

    @Test
    fun `at most five tenders on review per user`() {
        repeat(5) { tenders.create(owner, terms()) }
        val ex = assertThrows<BusinessRuleException> { tenders.create(owner, terms()) }
        assertEquals("TOO_MANY_TENDERS_ON_REVIEW", ex.code)
        tenders.create(Actors.user(), terms())
    }

    @Test
    fun `tender on review is invisible to other users`() {
        val id = tenders.create(owner, terms()).tender.id
        assertThrows<NotFoundException> { tenders.get(Actors.user(), id) }
        assertEquals(id, tenders.get(admin, id).tender.id)
    }

    @Test
    fun `only owner can edit`() {
        val id = tenders.create(owner, terms()).tender.id
        moderation.reject(admin, id, "Уточните сроки")
        val stranger = Actors.user()
        assertThrows<NotFoundException> { tenders.update(stranger, id, terms()) }
        tenders.update(owner, id, terms().copy(title = "Ремонт офиса, 2 этаж"))
        assertEquals(TenderStatus.ON_REVIEW, tenders.resubmit(owner, id).tender.status)
    }

    @Test
    fun `approve emits event and opens tender for bidding`() {
        val id = tenders.create(owner, terms()).tender.id
        moderation.approve(admin, id)
        assertEquals(1, events.ofType<TenderApproved>().size)
        assertEquals(TenderStatus.OPEN, catalog.find(id)!!.status)
    }

    @Test
    fun `owner cannot cancel open tender with bids, admin can`() {
        val id = tenders.create(owner, terms()).tender.id
        moderation.approve(admin, id)
        bids.counts[id] = 2

        assertEquals("TENDER_HAS_BIDS", assertThrows<BusinessRuleException> { tenders.cancel(owner, id, null) }.code)

        moderation.cancel(admin, id, "Нарушение правил площадки")
        val event = events.ofType<TenderCancelled>().single()
        assertEquals(true, event.byAdmin)
        assertEquals(TenderStatus.CANCELLED, repo.findById(id)!!.status)
    }

    @Test
    fun `public search accepts only public statuses`() {
        assertThrows<ValidationException> { tenders.search(TenderSearchFilter(status = TenderStatus.ON_REVIEW), PageRequest()) }
    }

    @Test
    fun `blocking an owner cancels all his unfinished tenders`() {
        val onReview = tenders.create(owner, terms()).tender.id
        val open = tenders.create(owner, terms()).tender.id
        moderation.approve(admin, open)

        val cancelled = tenders.cancelAllOfBlockedOwner(owner.userId, "спам")

        assertEquals(2, cancelled)
        assertEquals(TenderStatus.CANCELLED, repo.findById(onReview)!!.status)
        assertEquals(TenderStatus.CANCELLED, repo.findById(open)!!.status)
    }

    @Test
    fun `closing goes through catalog only after deadline`() {
        val id = tenders.create(owner, terms()).tender.id
        moderation.approve(admin, id)
        assertEquals(null, catalog.lockForClosing(id, clock.instant()))

        clock.advance(Duration.ofDays(6))
        val snapshot = catalog.lockForClosing(id, clock.instant())!!
        assertEquals(TenderStatus.OPEN, snapshot.status)
        assertEquals(TenderStatus.COMPLETED, catalog.markClosed(id, hasBids = true, now = clock.instant()))
    }
}

class FakeBidStatistics : BidStatistics {
    val counts = mutableMapOf<UUID, Int>()

    override fun countActiveBids(tenderId: UUID) = counts[tenderId] ?: 0

    override fun countActiveBids(tenderIds: Collection<UUID>) = tenderIds.associateWith { countActiveBids(it) }
}

class InMemoryTenderRepository : TenderRepository {
    private val rows = linkedMapOf<UUID, Tender>()

    override fun insert(tender: Tender) {
        rows[tender.id] = tender
    }

    override fun update(tender: Tender) {
        rows[tender.id] = tender
    }

    override fun findById(id: UUID) = rows[id]

    override fun findByIdForUpdate(id: UUID) = rows[id]

    override fun findByIdForShare(id: UUID) = rows[id]

    override fun lockExpiredOpen(id: UUID, now: Instant) =
        rows[id]?.takeIf { it.status == TenderStatus.OPEN && !it.terms.deadline.isAfter(now) }

    override fun findExpiredOpenIds(now: Instant, limit: Int) =
        rows.values.filter { it.status == TenderStatus.OPEN && !it.terms.deadline.isAfter(now) }.take(limit).map { it.id }

    override fun search(filter: TenderSearchFilter, page: PageRequest): Page<Tender> {
        val found = rows.values.filter { it.status == filter.status && it.isPublished }
        return Page.of(found.drop(page.offset.toInt()).take(page.size), page, found.size.toLong())
    }

    override fun findByOwner(ownerId: UUID, page: PageRequest): Page<Tender> {
        val found = rows.values.filter { it.ownerId == ownerId }
        return Page.of(found, page, found.size.toLong())
    }

    override fun findByStatus(status: TenderStatus, page: PageRequest): Page<Tender> {
        val found = rows.values.filter { it.status == status }
        return Page.of(found, page, found.size.toLong())
    }

    override fun countByOwnerAndStatus(ownerId: UUID, status: TenderStatus) =
        rows.values.count { it.ownerId == ownerId && it.status == status }

    override fun findIdsByOwnerAndStatusIn(ownerId: UUID, statuses: Set<TenderStatus>) =
        rows.values.filter { it.ownerId == ownerId && it.status in statuses }.map { it.id }
}
