package tenderhub.bidding.domain

import tenderhub.common.ConflictException
import tenderhub.common.validate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class BidStatus { ACTIVE, WITHDRAWN }

data class BidOffer(
    val price: BigDecimal,
    val deliveryDays: Int,
    val warrantyMonths: Int,
    val comment: String?,
) {
    init {
        validate(price.signum() > 0, "price", "INVALID_PRICE") { "Цена должна быть больше нуля" }
        validate(price.scale() <= 2, "price", "INVALID_PRICE") { "Цена указывается с точностью до копеек" }
        validate(deliveryDays in 1..MAX_DELIVERY_DAYS, "deliveryDays", "INVALID_DELIVERY_DAYS") {
            "Срок выполнения — от 1 до $MAX_DELIVERY_DAYS дней"
        }
        validate(warrantyMonths in 0..MAX_WARRANTY_MONTHS, "warrantyMonths", "INVALID_WARRANTY") {
            "Гарантия — от 0 до $MAX_WARRANTY_MONTHS месяцев"
        }
        validate(comment == null || comment.length <= 2000, "comment", "COMMENT_TOO_LONG") {
            "Комментарий не длиннее 2000 символов"
        }
    }

    companion object {
        const val MAX_DELIVERY_DAYS = 3650
        const val MAX_WARRANTY_MONTHS = 600
    }
}

data class BidResult(val place: Int, val score: BigDecimal)

class Bid private constructor(
    val id: UUID,
    val tenderId: UUID,
    val participantId: UUID,
    val offer: BidOffer,
    status: BidStatus,
    val submittedAt: Instant,
    val createdAt: Instant,
    result: BidResult?,
    version: Long,
) {
    var status: BidStatus = status
        private set
    var result: BidResult? = result
        private set
    var version: Long = version
        private set

    val isActive: Boolean get() = status == BidStatus.ACTIVE

    fun withdraw() {
        requireActive()
        status = BidStatus.WITHDRAWN
    }

    fun assignResult(result: BidResult) {
        requireActive()
        if (this.result != null) throw ConflictException("RESULT_ALREADY_FIXED", "Результат заявки уже зафиксирован")
        this.result = result
    }

    internal fun incrementVersion() {
        version++
    }

    private fun requireActive() {
        if (!isActive) throw ConflictException("BID_WITHDRAWN", "Заявка отозвана")
    }

    companion object {
        fun place(id: UUID, tenderId: UUID, participantId: UUID, offer: BidOffer, now: Instant) =
            Bid(id, tenderId, participantId, offer, BidStatus.ACTIVE, now, now, null, 0)

        fun restore(
            id: UUID,
            tenderId: UUID,
            participantId: UUID,
            offer: BidOffer,
            status: BidStatus,
            submittedAt: Instant,
            createdAt: Instant,
            result: BidResult?,
            version: Long,
        ) = Bid(id, tenderId, participantId, offer, status, submittedAt, createdAt, result, version)
    }
}
