package tenderhub.bidding

import tenderhub.bidding.application.BidRepository
import tenderhub.bidding.domain.Bid
import tenderhub.common.ConflictException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.tenders.api.TenderCatalog
import tenderhub.tenders.api.TenderSnapshot
import tenderhub.tenders.domain.TenderStatus
import java.time.Instant
import java.util.UUID

class FakeTenderCatalog : TenderCatalog {
    val tenders = mutableMapOf<UUID, TenderSnapshot>()

    fun put(snapshot: TenderSnapshot) {
        tenders[snapshot.id] = snapshot
    }

    override fun find(id: UUID) = tenders[id]

    override fun lockForBidding(id: UUID) = tenders[id]

    override fun findExpiredOpenTenderIds(now: Instant, limit: Int) =
        tenders.values.filter { it.status == TenderStatus.OPEN && !it.deadline.isAfter(now) }.map { it.id }.take(limit)

    override fun lockForClosing(id: UUID, now: Instant) =
        tenders[id]?.takeIf { it.status == TenderStatus.OPEN && !it.deadline.isAfter(now) }

    override fun markClosed(id: UUID, hasBids: Boolean, now: Instant): TenderStatus {
        val status = if (hasBids) TenderStatus.COMPLETED else TenderStatus.FAILED
        tenders[id] = tenders.getValue(id).copy(status = status)
        return status
    }
}

class InMemoryBidRepository : BidRepository {
    val rows = linkedMapOf<UUID, Bid>()

    override fun insert(bid: Bid) {
        if (rows.values.any { it.isActive && it.tenderId == bid.tenderId && it.participantId == bid.participantId }) {
            throw ConflictException("BID_ALREADY_EXISTS", "duplicate")
        }
        rows[bid.id] = bid
    }

    override fun update(bid: Bid) {
        rows[bid.id] = bid
    }

    override fun findById(id: UUID) = rows[id]

    override fun findByIdForUpdate(id: UUID) = rows[id]

    override fun findActive(tenderId: UUID, participantId: UUID) =
        rows.values.firstOrNull { it.isActive && it.tenderId == tenderId && it.participantId == participantId }

    override fun findActiveByTender(tenderId: UUID) = rows.values.filter { it.isActive && it.tenderId == tenderId }

    override fun findRanked(tenderId: UUID) =
        rows.values.filter { it.tenderId == tenderId && it.result != null }.sortedBy { it.result!!.place }

    override fun findByParticipant(participantId: UUID, page: PageRequest): Page<Bid> {
        val found = rows.values.filter { it.participantId == participantId }
        return Page.of(found, page, found.size.toLong())
    }

    override fun findActiveIdsByParticipant(participantId: UUID) =
        rows.values.filter { it.isActive && it.participantId == participantId && it.result == null }.map { it.id }

    override fun countActive(tenderId: UUID) = findActiveByTender(tenderId).size

    override fun countActive(tenderIds: Collection<UUID>) = tenderIds.associateWith { countActive(it) }
}
