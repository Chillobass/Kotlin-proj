package tenderhub.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.testcontainers.junit.jupiter.Testcontainers
import tenderhub.bidding.application.BidQueries
import tenderhub.bidding.application.BidService
import tenderhub.bidding.domain.BidOffer
import tenderhub.common.DomainException
import tenderhub.tenders.application.TenderService
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers(disabledWithoutDocker = true)
class ConcurrencyIT : IntegrationTestBase() {
    @Autowired
    lateinit var bidService: BidService

    @Autowired
    lateinit var bidQueries: BidQueries

    @Autowired
    lateinit var tenderService: TenderService

    private fun offer(price: String = "500000") = BidOffer(BigDecimal(price), 10, 12, null)

    private fun <T> race(tasks: List<() -> T>): Pair<Int, List<String>> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        val start = CountDownLatch(1)
        try {
            val futures = tasks.map { task ->
                pool.submit(
                    Callable {
                        start.await()
                        runCatching { task() }
                    },
                )
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val errors = results.mapNotNull { it.exceptionOrNull() }
            errors.filterNot { it is DomainException }.firstOrNull()?.let { throw it }
            return results.count { it.isSuccess } to errors.map { (it as DomainException).code }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `parallel duplicate bids of one participant - exactly one wins`() {
        val tenderId = UUID.fromString(openTender(newUser("owner")))
        val alice = newUser("alice").actor()

        val (succeeded, errors) = race(List(8) { i -> { bidService.submit(alice, tenderId, offer((500000 - i).toString())) } })

        assertEquals(1, succeeded)
        assertTrue(errors.all { it == "BID_ALREADY_EXISTS" }, "errors: $errors")
        assertEquals(1, bidQueries.countActiveBids(tenderId))
    }

    @Test
    fun `bids of different participants do not block each other`() {
        val tenderId = UUID.fromString(openTender(newUser("owner")))
        val participants = List(10) { newUser("p$it").actor() }

        val (succeeded, errors) = race(participants.map { p -> { bidService.submit(p, tenderId, offer()) } })

        assertEquals(10, succeeded, "errors: $errors")
        assertEquals(10, bidQueries.countActiveBids(tenderId))
    }

    @Test
    fun `owner cancellation racing with bids never leaves a cancelled tender with bids`() {
        repeat(5) {
            val owner = newUser("owner")
            val tenderId = UUID.fromString(openTender(owner))
            val bidders = List(4) { newUser("b$it").actor() }

            val tasks: List<() -> Any> = bidders.map { b -> { bidService.submit(b, tenderId, offer()) } } +
                listOf({ tenderService.cancel(owner.actor(), tenderId, "передумал") })
            val (_, errors) = race(tasks)

            val status = tenderService.get(owner.actor(), tenderId).tender.status
            val bids = bidQueries.countActiveBids(tenderId)
            if (status == TenderStatus.CANCELLED) {
                assertEquals(0, bids)
                assertTrue(errors.all { it == "TENDER_NOT_OPEN" }, "errors: $errors")
            } else {
                assertEquals(TenderStatus.OPEN, status)
                assertTrue(bids > 0)
                assertTrue("TENDER_HAS_BIDS" in errors, "errors: $errors")
            }
        }
    }
}
