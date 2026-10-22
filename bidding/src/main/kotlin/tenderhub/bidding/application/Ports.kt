package tenderhub.bidding.application

import tenderhub.bidding.domain.Bid
import tenderhub.common.Page
import tenderhub.common.PageRequest
import java.util.UUID

interface BidRepository {
    fun insert(bid: Bid)

    fun update(bid: Bid)

    fun findById(id: UUID): Bid?

    fun findByIdForUpdate(id: UUID): Bid?

    fun findActive(tenderId: UUID, participantId: UUID): Bid?

    fun findActiveByTender(tenderId: UUID): List<Bid>

    fun findRanked(tenderId: UUID): List<Bid>

    fun findByParticipant(participantId: UUID, page: PageRequest): Page<Bid>

    fun findActiveIdsByParticipant(participantId: UUID): List<UUID>

    fun countActive(tenderId: UUID): Int

    fun countActive(tenderIds: Collection<UUID>): Map<UUID, Int>
}
