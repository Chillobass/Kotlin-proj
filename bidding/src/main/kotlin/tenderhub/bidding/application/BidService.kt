package tenderhub.bidding.application

import org.slf4j.LoggerFactory
import tenderhub.bidding.domain.Bid
import tenderhub.bidding.domain.BidOffer
import tenderhub.bidding.domain.BiddingRules
import tenderhub.common.Actor
import tenderhub.common.ConflictException
import tenderhub.common.NotFoundException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.TransactionRunner
import tenderhub.tenders.api.TenderCatalog
import tenderhub.tenders.domain.TenderStatus
import java.time.Clock
import java.util.UUID

data class BidView(val bid: Bid, val tenderTitle: String, val tenderStatus: TenderStatus)

class BidService(
    private val bids: BidRepository,
    private val tenders: TenderCatalog,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(actor: Actor, tenderId: UUID, offer: BidOffer): BidView {
        actor.requireActiveUser()
        return tx.inTransaction {
            val tender = tenders.lockForBidding(tenderId) ?: throw tenderNotFound(tenderId)
            val now = clock.instant()
            BiddingRules.checkTenderAcceptsBids(tender, actor, now)
            BiddingRules.checkOffer(tender, offer)
            if (bids.findActive(tenderId, actor.userId) != null) {
                throw ConflictException(
                    "BID_ALREADY_EXISTS",
                    "Вы уже подали заявку на этот тендер",
                )
            }
            val bid = Bid.place(UUID.randomUUID(), tenderId, actor.userId, offer, now)
            bids.insert(bid)
            log.info("Bid placed: id={}, tender={}, participant={}", bid.id, tenderId, actor.userId)
            BidView(bid, tender.title, tender.status)
        }
    }

    fun myBids(actor: Actor, page: PageRequest): Page<BidView> = tx.readOnly {
        val result = bids.findByParticipant(actor.userId, page)
        val tendersById = result.items.map { it.tenderId }.distinct().associateWith { tenders.find(it) }
        result.map { bid ->
            val tender = tendersById[bid.tenderId]
            BidView(bid, tender?.title ?: "", tender?.status ?: TenderStatus.CANCELLED)
        }
    }

    fun myBidForTender(actor: Actor, tenderId: UUID): BidView = tx.readOnly {
        val tender = tenders.find(tenderId)?.takeIf { it.isVisibleTo(actor) } ?: throw tenderNotFound(tenderId)
        val bid = bids.findActive(tenderId, actor.userId)
            ?: throw NotFoundException("BID_NOT_FOUND", "У вас нет активной заявки на этот тендер")
        BidView(bid, tender.title, tender.status)
    }

    fun withdrawAllOfBlockedParticipant(participantId: UUID): Int = tx.inTransaction {
        val now = clock.instant()
        var withdrawn = 0
        for (bidId in bids.findActiveIdsByParticipant(participantId)) {
            val snapshot = bids.findById(bidId) ?: continue
            val tender = tenders.lockForBidding(snapshot.tenderId) ?: continue
            if (tender.status != TenderStatus.OPEN || !now.isBefore(tender.deadline)) continue
            val bid = bids.findByIdForUpdate(bidId)?.takeIf { it.isActive } ?: continue
            bid.withdraw()
            bids.update(bid)
            withdrawn++
        }
        if (withdrawn > 0) log.info("Withdrew {} bid(s) of blocked user {}", withdrawn, participantId)
        withdrawn
    }

    private fun bidNotFound(id: UUID) = NotFoundException("BID_NOT_FOUND", "Заявка $id не найдена")

    private fun tenderNotFound(id: UUID) = NotFoundException("TENDER_NOT_FOUND", "Тендер $id не найден")
}
