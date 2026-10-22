package tenderhub.bidding.domain

import tenderhub.tenders.domain.CriteriaWeights
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class RankedBid(val bid: Bid, val result: BidResult)

/*
 scores of active bids (see docs/business-rules.md, "Рейтинг заявок").
 For every criteria a normalized score in [0, 1] is computed relative to the best bid:
  - price:    minPrice / price (lower is better)
  - delivery: minDeliveryDays / days (the lower- the better)
  - warranty: months / maxWarrantyMonths  (higher is better, 0 if nobody offers warranty)
*/
// score = wPrice * price + wDelivery * delivery + wWarranty * warranty, от 0 до 100

object RatingCalculator {
    private val MATH = MathContext.DECIMAL64

    fun rank(bids: List<Bid>, weights: CriteriaWeights): List<RankedBid> {
        val active = bids.filter { it.isActive }
        if (active.isEmpty()) return emptyList()

        val minPrice = active.minOf { it.offer.price }
        val minDelivery = active.minOf { it.offer.deliveryDays }.toBigDecimal()
        val maxWarranty = active.maxOf { it.offer.warrantyMonths }.toBigDecimal()

        val scored = active.map { bid ->
            val pricePoints = minPrice.divide(bid.offer.price, MATH)
            val deliveryPoints = minDelivery.divide(bid.offer.deliveryDays.toBigDecimal(), MATH)
            val warrantyPoints =
                if (maxWarranty.signum() == 0) BigDecimal.ZERO
                else bid.offer.warrantyMonths.toBigDecimal().divide(maxWarranty, MATH)

            val score = weights.price.toBigDecimal() * pricePoints +
                weights.delivery.toBigDecimal() * deliveryPoints +
                weights.warranty.toBigDecimal() * warrantyPoints
            bid to score.setScale(2, RoundingMode.HALF_UP)
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<Bid, BigDecimal>> { it.second }
                    .thenBy { it.first.submittedAt }
                    .thenBy { it.first.id },
            )
            .mapIndexed { index, (bid, score) -> RankedBid(bid, BidResult(place = index + 1, score = score)) }
    }
}
