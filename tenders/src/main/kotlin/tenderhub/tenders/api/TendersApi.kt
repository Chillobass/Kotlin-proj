package tenderhub.tenders.api

import tenderhub.common.Actor
import tenderhub.common.DomainEvent
import tenderhub.tenders.domain.CriteriaWeights
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TenderSnapshot(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val status: TenderStatus,
    val maxPrice: BigDecimal,
    val deadline: Instant,
    val weights: CriteriaWeights,
    val published: Boolean,
) {
    fun isVisibleTo(actor: Actor): Boolean = published || actor.userId == ownerId || actor.isAdmin
}

interface TenderCatalog {
    fun find(id: UUID): TenderSnapshot?

    // Shared row lock: many bids may be placed concurrently, closing/cancelling waits for them
    fun lockForBidding(id: UUID): TenderSnapshot?

    fun findExpiredOpenTenderIds(now: Instant, limit: Int): List<UUID>

    fun lockForClosing(id: UUID, now: Instant): TenderSnapshot?

    fun markClosed(id: UUID, hasBids: Boolean, now: Instant): TenderStatus
}

interface BidStatistics {
    fun countActiveBids(tenderId: UUID): Int

    fun countActiveBids(tenderIds: Collection<UUID>): Map<UUID, Int>
}

data class TenderSubmitted(
    val tenderId: UUID,
    val ownerId: UUID,
    val title: String,
    override val occurredAt: Instant,
) : DomainEvent

data class TenderApproved(
    val tenderId: UUID,
    val ownerId: UUID,
    val title: String,
    val deadline: Instant,
    override val occurredAt: Instant,
) : DomainEvent

data class TenderRejected(
    val tenderId: UUID,
    val ownerId: UUID,
    val title: String,
    val reason: String,
    override val occurredAt: Instant,
) : DomainEvent

data class TenderCancelled(
    val tenderId: UUID,
    val ownerId: UUID,
    val title: String,
    val reason: String,
    val byAdmin: Boolean,
    override val occurredAt: Instant,
) : DomainEvent
