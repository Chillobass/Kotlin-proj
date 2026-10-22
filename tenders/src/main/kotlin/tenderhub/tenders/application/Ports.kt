package tenderhub.tenders.application

import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.tenders.domain.Tender
import tenderhub.tenders.domain.TenderCategory
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface TenderRepository {
    fun insert(tender: Tender)

    fun update(tender: Tender)

    fun findById(id: UUID): Tender?

    fun findByIdForUpdate(id: UUID): Tender?

    fun findByIdForShare(id: UUID): Tender?

    fun lockExpiredOpen(id: UUID, now: Instant): Tender?

    fun findExpiredOpenIds(now: Instant, limit: Int): List<UUID>

    fun search(filter: TenderSearchFilter, page: PageRequest): Page<Tender>

    fun findByOwner(ownerId: UUID, page: PageRequest): Page<Tender>

    fun findByStatus(status: TenderStatus, page: PageRequest): Page<Tender>

    fun countByOwnerAndStatus(ownerId: UUID, status: TenderStatus): Int

    fun findIdsByOwnerAndStatusIn(ownerId: UUID, statuses: Set<TenderStatus>): List<UUID>
}

data class TenderSearchFilter(
    val status: TenderStatus = TenderStatus.OPEN,
    val category: TenderCategory? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val deadlineFrom: Instant? = null,
    val deadlineTo: Instant? = null,
    val text: String? = null,
)
