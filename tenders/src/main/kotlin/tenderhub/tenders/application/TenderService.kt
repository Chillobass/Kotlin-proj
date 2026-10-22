package tenderhub.tenders.application

import org.slf4j.LoggerFactory
import tenderhub.common.Actor
import tenderhub.common.BusinessRuleException
import tenderhub.common.EventPublisher
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.TransactionRunner
import tenderhub.common.ValidationException
import tenderhub.tenders.api.BidStatistics
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderSubmitted
import tenderhub.tenders.domain.Tender
import tenderhub.tenders.domain.TenderPolicy
import tenderhub.tenders.domain.TenderStatus
import tenderhub.tenders.domain.TenderTerms
import java.time.Clock
import java.util.UUID

data class TenderView(val tender: Tender, val activeBids: Int)

class TenderService(
    private val tenders: TenderRepository,
    private val bidStatistics: BidStatistics,
    private val policy: TenderPolicy,
    private val tx: TransactionRunner,
    private val events: EventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(actor: Actor, terms: TenderTerms): TenderView {
        actor.requireActiveUser()
        return tx.inTransaction {
            checkReviewQuota(actor.userId)
            val now = clock.instant()
            val tender = Tender.submit(UUID.randomUUID(), actor.userId, terms, policy, now)
            tenders.insert(tender)
            events.publish(TenderSubmitted(tender.id, tender.ownerId, terms.title, now))
            log.info("Tender submitted for review: id={}, owner={}", tender.id, actor.userId)
            TenderView(tender, 0)
        }
    }

    fun update(actor: Actor, tenderId: UUID, terms: TenderTerms): TenderView {
        actor.requireActiveUser()
        return tx.inTransaction {
            val tender = lockOwned(actor, tenderId)
            tender.edit(terms, policy, clock.instant())
            tenders.update(tender)
            log.info("Tender edited: id={}", tender.id)
            TenderView(tender, 0)
        }
    }

    fun resubmit(actor: Actor, tenderId: UUID): TenderView {
        actor.requireActiveUser()
        return tx.inTransaction {
            val tender = lockOwned(actor, tenderId)
            checkReviewQuota(actor.userId)
            val now = clock.instant()
            tender.resubmit(policy, now)
            tenders.update(tender)
            events.publish(TenderSubmitted(tender.id, tender.ownerId, tender.terms.title, now))
            log.info("Tender resubmitted for review: id={}", tender.id)
            TenderView(tender, 0)
        }
    }

    fun cancel(actor: Actor, tenderId: UUID, reason: String?): TenderView {
        actor.requireActiveUser()
        return tx.inTransaction {
            // Exclusive lock: a concurrent bid holds a shared lock, so "no bids" stays true until commit.
            val tender = lockOwned(actor, tenderId)
            val activeBids = bidStatistics.countActiveBids(tender.id)
            val now = clock.instant()
            tender.cancelByOwner(reason, activeBids > 0, now)
            tenders.update(tender)
            events.publish(
                TenderCancelled(tender.id, tender.ownerId, tender.terms.title, tender.statusReason!!, false, now),
            )
            log.info("Tender cancelled by owner: id={}", tender.id)
            TenderView(tender, activeBids)
        }
    }

    fun get(actor: Actor, tenderId: UUID): TenderView = tx.readOnly {
        val tender = tenders.findById(tenderId)?.takeIf { it.isVisibleTo(actor) } ?: throw tenderNotFound(tenderId)
        TenderView(tender, bidStatistics.countActiveBids(tender.id))
    }

    fun search(filter: TenderSearchFilter, page: PageRequest): Page<TenderView> {
        if (filter.status !in PUBLIC_STATUSES) {
            throw ValidationException(
                "INVALID_STATUS_FILTER",
                "Доступные статусы для поиска: ${PUBLIC_STATUSES.joinToString()}",
                "status",
            )
        }
        if (filter.minPrice != null && filter.maxPrice != null && filter.minPrice > filter.maxPrice) {
            throw ValidationException("INVALID_PRICE_RANGE", "minPrice больше maxPrice", "minPrice")
        }
        return tx.readOnly { withBidCounts(tenders.search(filter, page)) }
    }

    fun mine(actor: Actor, page: PageRequest): Page<TenderView> =
        tx.readOnly { withBidCounts(tenders.findByOwner(actor.userId, page)) }

    fun cancelAllOfBlockedOwner(ownerId: UUID, reason: String) = tx.inTransaction {
        val ids = tenders.findIdsByOwnerAndStatusIn(ownerId, setOf(TenderStatus.ON_REVIEW, TenderStatus.REJECTED, TenderStatus.OPEN))
        val now = clock.instant()
        var cancelled = 0
        for (id in ids) {
            val tender = tenders.findByIdForUpdate(id) ?: continue
            if (tender.status == TenderStatus.OPEN && !now.isBefore(tender.terms.deadline)) continue
            if (tender.status !in setOf(TenderStatus.ON_REVIEW, TenderStatus.REJECTED, TenderStatus.OPEN)) continue
            val fullReason = "Владелец заблокирован: $reason"
            tender.cancelByAdmin(fullReason.take(1000), now)
            tenders.update(tender)
            events.publish(TenderCancelled(tender.id, tender.ownerId, tender.terms.title, tender.statusReason!!, true, now))
            cancelled++
        }
        if (cancelled > 0) log.info("Cancelled {} tender(s) of blocked user {}", cancelled, ownerId)
        cancelled
    }

    private fun lockOwned(actor: Actor, tenderId: UUID): Tender {
        val tender = tenders.findByIdForUpdate(tenderId)?.takeIf { it.isVisibleTo(actor) } ?: throw tenderNotFound(tenderId)
        tender.requireOwner(actor)
        return tender
    }

    private fun checkReviewQuota(ownerId: UUID) {
        if (tenders.countByOwnerAndStatus(ownerId, TenderStatus.ON_REVIEW) >= policy.maxOnReviewPerOwner) {
            throw BusinessRuleException(
                "TOO_MANY_TENDERS_ON_REVIEW",
                "На модерации может быть не более ${policy.maxOnReviewPerOwner} ваших тендеров одновременно",
            )
        }
    }

    private fun withBidCounts(page: Page<Tender>): Page<TenderView> {
        val counts = bidStatistics.countActiveBids(page.items.map { it.id })
        return page.map { TenderView(it, counts[it.id] ?: 0) }
    }

    companion object {
        val PUBLIC_STATUSES = setOf(TenderStatus.OPEN, TenderStatus.COMPLETED, TenderStatus.FAILED, TenderStatus.CANCELLED)

        fun tenderNotFound(id: UUID) = NotFoundException("TENDER_NOT_FOUND", "Тендер $id не найден")
    }
}
