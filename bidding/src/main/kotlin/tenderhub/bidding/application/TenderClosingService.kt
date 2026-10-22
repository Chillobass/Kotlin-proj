package tenderhub.bidding.application

import org.slf4j.LoggerFactory
import tenderhub.bidding.api.Standing
import tenderhub.bidding.api.TenderResultsPublished
import tenderhub.bidding.domain.RatingCalculator
import tenderhub.common.Actor
import tenderhub.common.EventPublisher
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.TenderCatalog
import java.time.Clock
import java.util.UUID

class TenderClosingService(
    private val bids: BidRepository,
    private val tenders: TenderCatalog,
    private val tx: TransactionRunner,
    private val events: EventPublisher,
    private val clock: Clock,
    private val batchSize: Int = 100,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun closeExpired(): Int {
        var closedTotal = 0
        while (true) {
            val now = clock.instant()
            val ids = tenders.findExpiredOpenTenderIds(now, batchSize)
            if (ids.isEmpty()) break
            val closed = ids.count { id ->
                try {
                    closeOne(id)
                } catch (e: Exception) {
                    log.error("Failed to close tender {}", id, e)
                    false
                }
            }
            closedTotal += closed
            if (closed == 0 || ids.size < batchSize) break
        }
        return closedTotal
    }

    fun closeExpired(actor: Actor): Int {
        actor.requireAdmin()
        return closeExpired()
    }

    fun closeOne(tenderId: UUID): Boolean = tx.inTransaction {
        val now = clock.instant()
        val tender = tenders.lockForClosing(tenderId, now) ?: return@inTransaction false

        val ranked = RatingCalculator.rank(bids.findActiveByTender(tenderId), tender.weights)
        ranked.forEach { (bid, result) ->
            bid.assignResult(result)
            bids.update(bid)
        }
        val status = tenders.markClosed(tenderId, ranked.isNotEmpty(), now)

        events.publish(
            TenderResultsPublished(
                tenderId = tender.id,
                ownerId = tender.ownerId,
                title = tender.title,
                status = status,
                standings = ranked.map { Standing(it.bid.participantId, it.result.place, it.result.score) },
                occurredAt = now,
            ),
        )
        log.info("Tender {} closed with status {} and {} ranked bid(s)", tenderId, status, ranked.size)
        true
    }
}
