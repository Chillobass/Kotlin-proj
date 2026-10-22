package tenderhub.bidding.application

import tenderhub.bidding.domain.BidOffer
import tenderhub.common.Actor
import tenderhub.common.ConflictException
import tenderhub.common.NotFoundException
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.TenderCatalog
import tenderhub.tenders.domain.TenderStatus
import tenderhub.users.api.UserDirectory
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RatingEntry(
    val place: Int,
    val score: BigDecimal,
    val bidId: UUID,
    val participantId: UUID,
    val participantName: String,
    val offer: BidOffer,
    val submittedAt: Instant,
)

data class TenderResults(
    val tenderId: UUID,
    val title: String,
    val status: TenderStatus,
    val rating: List<RatingEntry>,
) {
    val winner: RatingEntry? get() = rating.firstOrNull()
}

class ResultsService(
    private val bids: BidRepository,
    private val tenders: TenderCatalog,
    private val users: UserDirectory,
    private val tx: TransactionRunner,
) {
    fun results(actor: Actor, tenderId: UUID): TenderResults = tx.readOnly {
        val tender = tenders.find(tenderId)?.takeIf { it.isVisibleTo(actor) }
            ?: throw NotFoundException("TENDER_NOT_FOUND", "Тендер $tenderId не найден")
        when (tender.status) {
            TenderStatus.COMPLETED, TenderStatus.FAILED -> Unit
            TenderStatus.CANCELLED -> throw ConflictException("TENDER_CANCELLED", "Тендер отменён, итогов нет")
            else -> throw ConflictException(
                "RESULTS_NOT_AVAILABLE",
                "Рейтинг будет опубликован после окончания приёма заявок",
            )
        }
        val ranked = bids.findRanked(tenderId)
        val names = users.displayNames(ranked.map { it.participantId })
        TenderResults(
            tenderId = tender.id,
            title = tender.title,
            status = tender.status,
            rating = ranked.map { bid ->
                val result = bid.result!!
                RatingEntry(
                    place = result.place,
                    score = result.score,
                    bidId = bid.id,
                    participantId = bid.participantId,
                    participantName = names[bid.participantId] ?: "",
                    offer = bid.offer,
                    submittedAt = bid.submittedAt,
                )
            },
        )
    }
}
