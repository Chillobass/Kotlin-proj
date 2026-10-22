package tenderhub.bidding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tenderhub.bidding.domain.Bid
import tenderhub.bidding.domain.BidOffer
import tenderhub.bidding.domain.RatingCalculator
import tenderhub.tenders.domain.CriteriaWeights
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RatingCalculatorTest {
    private val tenderId = UUID.randomUUID()
    private val t0 = Instant.parse("2026-01-10T10:00:00Z")

    private fun bid(price: String, days: Int, warranty: Int, submittedAt: Instant = t0) =
        Bid.place(UUID.randomUUID(), tenderId, UUID.randomUUID(), BidOffer(BigDecimal(price), days, warranty, null), submittedAt)

    @Test
    fun `example from the business rules`() {
        val a = bid("900000", 30, 12)
        val b = bid("1000000", 20, 6)
        val c = bid("950000", 40, 12)

        val ranked = RatingCalculator.rank(listOf(c, b, a), CriteriaWeights(60, 30, 10))

        assertEquals(listOf(a, b, c), ranked.map { it.bid })
        assertEquals(listOf(1, 2, 3), ranked.map { it.result.place })
        assertEquals(BigDecimal("90.00"), ranked[0].result.score)
        assertEquals(BigDecimal("89.00"), ranked[1].result.score)
        assertEquals(BigDecimal("81.84"), ranked[2].result.score)
    }

    @Test
    fun `single bid gets maximal score`() {
        val ranked = RatingCalculator.rank(listOf(bid("100", 5, 3)), CriteriaWeights(40, 40, 20))
        assertEquals(BigDecimal("100.00"), ranked.single().result.score)
    }

    @Test
    fun `nobody offers warranty - warranty criterion gives zero points to everyone`() {
        val ranked = RatingCalculator.rank(listOf(bid("100", 10, 0), bid("200", 10, 0)), CriteriaWeights(50, 30, 20))
        assertEquals(BigDecimal("80.00"), ranked[0].result.score)
        assertEquals(BigDecimal("55.00"), ranked[1].result.score)
    }

    @Test
    fun `tie is broken by earlier submission`() {
        val late = bid("100", 10, 12, t0.plusSeconds(60))
        val early = bid("100", 10, 12, t0)
        val ranked = RatingCalculator.rank(listOf(late, early), CriteriaWeights(100, 0, 0))
        assertEquals(listOf(early, late), ranked.map { it.bid })
        assertEquals(ranked[0].result.score, ranked[1].result.score)
    }

    @Test
    fun `withdrawn bids are ignored`() {
        val withdrawn = bid("1", 1, 100).also { it.withdraw() }
        val active = bid("100", 10, 1)
        val ranked = RatingCalculator.rank(listOf(withdrawn, active), CriteriaWeights(34, 33, 33))
        assertEquals(listOf(active), ranked.map { it.bid })
        assertEquals(BigDecimal("100.00"), ranked.single().result.score)
    }

    @Test
    fun `scores are always between 0 and 100`() {
        val bids = (1..20).map { bid((it * 1000).toString(), it, it % 5) }
        RatingCalculator.rank(bids, CriteriaWeights(20, 50, 30)).forEach {
            assertTrue(it.result.score >= BigDecimal.ZERO && it.result.score <= BigDecimal("100.00"))
        }
    }

    @Test
    fun `no bids - empty rating`() {
        assertTrue(RatingCalculator.rank(emptyList(), CriteriaWeights(100, 0, 0)).isEmpty())
    }
}
