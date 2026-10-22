package tenderhub.bidding.api

import tenderhub.common.DomainEvent
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface BidderDirectory {
    fun activeParticipantIds(tenderId: UUID): List<UUID>
}

data class Standing(val participantId: UUID, val place: Int, val score: BigDecimal)

data class TenderResultsPublished(
    val tenderId: UUID,
    val ownerId: UUID,
    val title: String,
    val status: TenderStatus,
    val standings: List<Standing>,
    override val occurredAt: Instant,
) : DomainEvent
