package tenderhub.bidding.domain

import tenderhub.common.Actor
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.ForbiddenException
import tenderhub.common.NotFoundException
import tenderhub.tenders.api.TenderSnapshot
import tenderhub.tenders.domain.TenderStatus
import java.time.Instant

object BiddingRules {
    fun checkTenderAcceptsBids(tender: TenderSnapshot, actor: Actor, now: Instant) {
        actor.requireActiveUser()
        if (!tender.isVisibleTo(actor)) {
            throw NotFoundException("TENDER_NOT_FOUND", "Тендер ${tender.id} не найден")
        }
        if (tender.ownerId == actor.userId) {
            throw ForbiddenException("OWN_TENDER", "Нельзя подавать заявку на собственный тендер")
        }
        if (tender.status != TenderStatus.OPEN) {
            throw ConflictException("TENDER_NOT_OPEN", "Тендер не принимает заявки (статус ${tender.status})")
        }
        if (!now.isBefore(tender.deadline)) {
            throw ConflictException("TENDER_DEADLINE_PASSED", "Срок приёма заявок истёк")
        }
    }

    fun checkOffer(tender: TenderSnapshot, offer: BidOffer) {
        if (offer.price > tender.maxPrice) {
            throw BusinessRuleException(
                "PRICE_ABOVE_MAX",
                "Цена ${offer.price.toPlainString()} выше начальной цены тендера ${tender.maxPrice.toPlainString()}",
            )
        }
    }
}
