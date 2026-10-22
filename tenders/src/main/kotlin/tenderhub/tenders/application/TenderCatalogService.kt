package tenderhub.tenders.application

import org.slf4j.LoggerFactory
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.TenderCatalog
import tenderhub.tenders.api.TenderSnapshot
import tenderhub.tenders.application.TenderService.Companion.tenderNotFound
import tenderhub.tenders.domain.Tender
import tenderhub.tenders.domain.TenderStatus
import java.time.Instant
import java.util.UUID

class TenderCatalogService(
    private val tenders: TenderRepository,
    private val tx: TransactionRunner,
) : TenderCatalog {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun find(id: UUID): TenderSnapshot? = tx.readOnly { tenders.findById(id)?.toSnapshot() }

    override fun lockForBidding(id: UUID): TenderSnapshot? = tx.inTransaction { tenders.findByIdForShare(id)?.toSnapshot() }

    override fun findExpiredOpenTenderIds(now: Instant, limit: Int): List<UUID> =
        tx.readOnly { tenders.findExpiredOpenIds(now, limit) }

    override fun lockForClosing(id: UUID, now: Instant): TenderSnapshot? =
        tx.inTransaction { tenders.lockExpiredOpen(id, now)?.toSnapshot() }

    override fun markClosed(id: UUID, hasBids: Boolean, now: Instant): TenderStatus = tx.inTransaction {
        val tender = tenders.findByIdForUpdate(id) ?: throw tenderNotFound(id)
        tender.close(hasBids, now)
        tenders.update(tender)
        log.info("Tender closed: id={}, status={}", tender.id, tender.status)
        tender.status
    }

    private fun Tender.toSnapshot() = TenderSnapshot(
        id = id,
        ownerId = ownerId,
        title = terms.title,
        status = status,
        maxPrice = terms.maxPrice,
        deadline = terms.deadline,
        weights = terms.weights,
        published = isPublished,
    )
}
