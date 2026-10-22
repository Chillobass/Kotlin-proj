package tenderhub.tenders.domain

import tenderhub.common.BusinessRuleException
import tenderhub.common.businessRule
import tenderhub.common.validate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

enum class TenderCategory { IT, CONSTRUCTION, SUPPLIES, SERVICES, LOGISTICS, OTHER }

enum class TenderStatus {
    ON_REVIEW,
    REJECTED,
    OPEN,
    CANCELLED,
    COMPLETED,
    FAILED,
    ;

    val isClosed: Boolean get() = this == COMPLETED || this == FAILED
}

data class CriteriaWeights(val price: Int, val delivery: Int, val warranty: Int) {
    init {
        validate(price >= 0 && delivery >= 0 && warranty >= 0, "weights", "NEGATIVE_WEIGHT") {
            "Веса критериев не могут быть отрицательными"
        }
        businessRule(price + delivery + warranty == 100, "WEIGHTS_SUM_NOT_100") {
            "Сумма весов критериев должна быть равна 100 (сейчас ${price + delivery + warranty})"
        }
    }
}

data class TenderTerms(
    val title: String,
    val description: String,
    val category: TenderCategory,
    val maxPrice: BigDecimal,
    val deadline: Instant,
    val weights: CriteriaWeights,
) {
    init {
        validate(title.isNotBlank() && title.length in 3..200, "title", "INVALID_TITLE") {
            "Название должно содержать от 3 до 200 символов"
        }
        validate(description.isNotBlank() && description.length <= 5000, "description", "INVALID_DESCRIPTION") {
            "Описание обязательно и не длиннее 5000 символов"
        }
        validate(maxPrice.signum() > 0, "maxPrice", "INVALID_MAX_PRICE") { "Начальная цена должна быть больше нуля" }
        validate(maxPrice.scale() <= 2, "maxPrice", "INVALID_MAX_PRICE") { "Цена указывается с точностью до копеек" }
        validate(maxPrice < MAX_MONEY, "maxPrice", "INVALID_MAX_PRICE") { "Слишком большая начальная цена" }
    }

    companion object {
        val MAX_MONEY: BigDecimal = BigDecimal("1000000000000000") // fits numeric(19, 2)
    }
}

data class TenderPolicy(
    val minDeadlineOffset: Duration = Duration.ofHours(24),
    val maxDeadlineOffset: Duration = Duration.ofDays(90),
    val minOpenDuration: Duration = Duration.ofHours(24),
    val maxOnReviewPerOwner: Int = 5,
) {
    fun checkDeadline(deadline: Instant, now: Instant) {
        if (deadline.isBefore(now.plus(minDeadlineOffset))) {
            throw BusinessRuleException(
                "DEADLINE_TOO_EARLY",
                "Срок приёма заявок должен быть не раньше чем через ${minDeadlineOffset.pretty()}",
            )
        }
        if (deadline.isAfter(now.plus(maxDeadlineOffset))) {
            throw BusinessRuleException(
                "DEADLINE_TOO_LATE",
                "Срок приёма заявок должен быть не позже чем через ${maxDeadlineOffset.pretty()}",
            )
        }
    }
}

internal fun Duration.pretty(): String = when {
    toMinutes() < 60 -> "${toMinutes()} мин."
    toHours() < 48 -> "${toHours()} ч."
    else -> "${toDays()} дн."
}
