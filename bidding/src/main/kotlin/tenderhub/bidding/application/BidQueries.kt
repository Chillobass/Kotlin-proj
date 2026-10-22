package tenderhub.bidding.application

import tenderhub.bidding.api.BidderDirectory
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.BidStatistics
import java.util.UUID

class BidQueries(
    private val bids: BidRepository,
    private val tx: TransactionRunner,
) : BidStatistics, BidderDirectory {
    override fun countActiveBids(tenderId: UUID): Int = tx.readOnly { bids.countActive(tenderId) }

    override fun countActiveBids(tenderIds: Collection<UUID>): Map<UUID, Int> =
        if (tenderIds.isEmpty()) emptyMap() else tx.readOnly { bids.countActive(tenderIds) }

    override fun activeParticipantIds(tenderId: UUID): List<UUID> =
        tx.readOnly { bids.findActiveByTender(tenderId).map { it.participantId }.distinct() }
}
