package tenderhub.bidding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tenderhub.bidding.api.TenderResultsPublished
import tenderhub.bidding.application.BidService
import tenderhub.bidding.application.ResultsService
import tenderhub.bidding.application.TenderClosingService
import tenderhub.bidding.domain.BidOffer
import tenderhub.bidding.domain.BidStatus
import tenderhub.common.Actors
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.ForbiddenException
import tenderhub.common.InPlaceTransactionRunner
import tenderhub.common.MutableClock
import tenderhub.common.NotFoundException
import tenderhub.common.RecordingEventPublisher
import tenderhub.common.ValidationException
import tenderhub.tenders.api.TenderSnapshot
import tenderhub.tenders.domain.CriteriaWeights
import tenderhub.tenders.domain.TenderStatus
import tenderhub.users.api.UserDirectory
import java.math.BigDecimal
import java.time.Duration
import java.util.UUID

class BidServiceTest {
    private val clock = MutableClock()
    private val catalog = FakeTenderCatalog()
    private val bids = InMemoryBidRepository()
    private val events = RecordingEventPublisher()
    private val users = object : UserDirectory {
        override fun adminIds() = emptyList<UUID>()

        override fun displayNames(ids: Collection<UUID>) = ids.associateWith { "user-$it" }
    }

    private val bidService = BidService(bids, catalog, InPlaceTransactionRunner, clock)
    private val results = ResultsService(bids, catalog, users, InPlaceTransactionRunner)
    private val closing = TenderClosingService(bids, catalog, InPlaceTransactionRunner, events, clock)

    private val owner = Actors.user()
    private val alice = Actors.user()
    private val bob = Actors.user()

    private fun openTender(status: TenderStatus = TenderStatus.OPEN, published: Boolean = true): TenderSnapshot {
        val snapshot = TenderSnapshot(
            id = UUID.randomUUID(),
            ownerId = owner.userId,
            title = "Поставка серверов",
            status = status,
            maxPrice = BigDecimal("1000000"),
            deadline = clock.instant().plus(Duration.ofDays(3)),
            weights = CriteriaWeights(60, 30, 10),
            published = published,
        )
        catalog.put(snapshot)
        return snapshot
    }

    private fun offer(price: String = "900000", days: Int = 30, warranty: Int = 12) =
        BidOffer(BigDecimal(price), days, warranty, null)

    @Test
    fun `participant places a bid on an open tender`() {
        val tender = openTender()
        val view = bidService.submit(alice, tender.id, offer())
        assertEquals(BidStatus.ACTIVE, view.bid.status)
        assertEquals(1, bids.countActive(tender.id))
    }

    @Test
    fun `owner, admin and blocked users cannot bid`() {
        val tender = openTender()
        assertEquals("OWN_TENDER", assertThrows<ForbiddenException> { bidService.submit(owner, tender.id, offer()) }.code)
        assertThrows<ForbiddenException> { bidService.submit(Actors.admin(), tender.id, offer()) }
        assertThrows<ForbiddenException> { bidService.submit(Actors.blockedUser(), tender.id, offer()) }
    }

    @Test
    fun `price above the tender max price is rejected`() {
        val tender = openTender()
        val ex = assertThrows<BusinessRuleException> { bidService.submit(alice, tender.id, offer(price = "1000000.01")) }
        assertEquals("PRICE_ABOVE_MAX", ex.code)
        bidService.submit(alice, tender.id, offer(price = "1000000"))
    }

    @Test
    fun `malformed offers are rejected by the domain`() {
        assertThrows<ValidationException> { offer(price = "0") }
        assertThrows<ValidationException> { offer(days = 0) }
        assertThrows<ValidationException> { offer(warranty = -1) }
    }

    @Test
    fun `submitted bid is final - a second one is rejected`() {
        val tender = openTender()
        bidService.submit(alice, tender.id, offer())
        val ex = assertThrows<ConflictException> { bidService.submit(alice, tender.id, offer(price = "800000")) }
        assertEquals("BID_ALREADY_EXISTS", ex.code)
        assertEquals(BigDecimal("900000"), bids.findActive(tender.id, alice.userId)!!.offer.price)
    }

    @Test
    fun `bids are not accepted after the deadline even before the tender is closed`() {
        val tender = openTender()
        clock.advance(Duration.ofDays(3))
        val ex = assertThrows<ConflictException> { bidService.submit(alice, tender.id, offer()) }
        assertEquals("TENDER_DEADLINE_PASSED", ex.code)
    }

    @Test
    fun `unpublished tender looks non-existent to bidders`() {
        val tender = openTender(status = TenderStatus.ON_REVIEW, published = false)
        assertThrows<NotFoundException> { bidService.submit(alice, tender.id, offer()) }
    }

    @Test
    fun `results are hidden until the tender is closed`() {
        val tender = openTender()
        bidService.submit(alice, tender.id, offer())
        val ex = assertThrows<ConflictException> { results.results(Actors.admin(), tender.id) }
        assertEquals("RESULTS_NOT_AVAILABLE", ex.code)
    }

    @Test
    fun `closing ranks bids, freezes the result and publishes it`() {
        val tender = openTender()
        val a = bidService.submit(alice, tender.id, offer("900000", 30, 12)).bid
        val b = bidService.submit(bob, tender.id, offer("1000000", 20, 6)).bid
        val carol = Actors.user()
        val c = bidService.submit(carol, tender.id, offer("950000", 40, 12)).bid
        bidService.withdrawAllOfBlockedParticipant(carol.userId)

        clock.advance(Duration.ofDays(3))
        assertEquals(1, closing.closeExpired())

        val rating = results.results(Actors.user(), tender.id)
        assertEquals(TenderStatus.COMPLETED, rating.status)
        assertEquals(listOf(a.id, b.id), rating.rating.map { it.bidId })
        assertEquals(alice.userId, rating.winner!!.participantId)
        assertNull(bids.findById(c.id)!!.result)

        val event = events.ofType<TenderResultsPublished>().single()
        assertEquals(listOf(1, 2), event.standings.map { it.place })

        assertEquals(0, closing.closeExpired())
    }

    @Test
    fun `tender without bids fails`() {
        val tender = openTender()
        clock.advance(Duration.ofDays(4))
        closing.closeExpired()
        val rating = results.results(alice, tender.id)
        assertEquals(TenderStatus.FAILED, rating.status)
        assertEquals(0, rating.rating.size)
    }

    @Test
    fun `blocked participant loses active bids in open tenders`() {
        val tender = openTender()
        val bid = bidService.submit(alice, tender.id, offer()).bid
        assertEquals(1, bidService.withdrawAllOfBlockedParticipant(alice.userId))
        assertEquals(BidStatus.WITHDRAWN, bids.findById(bid.id)!!.status)
    }
}
