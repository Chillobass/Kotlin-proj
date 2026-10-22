package tenderhub.tenders.application

import org.slf4j.LoggerFactory
import tenderhub.common.Actor
import tenderhub.common.EventPublisher
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.BidStatistics
import tenderhub.tenders.api.TenderApproved
import tenderhub.tenders.api.TenderCancelled
import tenderhub.tenders.api.TenderRejected
import tenderhub.tenders.application.TenderService.Companion.tenderNotFound
import tenderhub.tenders.domain.TenderPolicy
import tenderhub.tenders.domain.TenderStatus
import java.time.Clock
import java.util.UUID

class ModerationService(
    private val tenders: TenderRepository,
    private val bidStatistics: BidStatistics,
    private val policy: TenderPolicy,
    private val tx: TransactionRunner,
    private val events: EventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun reviewQueue(actor: Actor, page: PageRequest): Page<TenderView> {
        actor.requireAdmin()
        return tx.readOnly { tenders.findByStatus(TenderStatus.ON_REVIEW, page).map { TenderView(it, 0) } }
    }

    fun approve(actor: Actor, tenderId: UUID): TenderView {
        actor.requireAdmin()
        return tx.inTransaction {
            val tender = tenders.findByIdForUpdate(tenderId) ?: throw tenderNotFound(tenderId)
            val now = clock.instant()
            tender.approve(actor.userId, policy, now)
            tenders.update(tender)
            events.publish(TenderApproved(tender.id, tender.ownerId, tender.terms.title, tender.terms.deadline, now))
            log.info("Tender approved: id={}, admin={}", tender.id, actor.userId)
            TenderView(tender, 0)
        }
    }

    fun reject(actor: Actor, tenderId: UUID, reason: String): TenderView {
        actor.requireAdmin()
        return tx.inTransaction {
            val tender = tenders.findByIdForUpdate(tenderId) ?: throw tenderNotFound(tenderId)
            val now = clock.instant()
            tender.reject(actor.userId, reason, now)
            tenders.update(tender)
            events.publish(TenderRejected(tender.id, tender.ownerId, tender.terms.title, tender.statusReason!!, now))
            log.info("Tender rejected: id={}, admin={}", tender.id, actor.userId)
            TenderView(tender, 0)
        }
    }

    fun cancel(actor: Actor, tenderId: UUID, reason: String): TenderView {
        actor.requireAdmin()
        return tx.inTransaction {
            val tender = tenders.findByIdForUpdate(tenderId) ?: throw tenderNotFound(tenderId)
            val now = clock.instant()
            tender.cancelByAdmin(reason, now)
            tenders.update(tender)
            events.publish(TenderCancelled(tender.id, tender.ownerId, tender.terms.title, tender.statusReason!!, true, now))
            log.info("Tender cancelled by admin: id={}, admin={}", tender.id, actor.userId)
            TenderView(tender, bidStatistics.countActiveBids(tender.id))
        }
    }
}
