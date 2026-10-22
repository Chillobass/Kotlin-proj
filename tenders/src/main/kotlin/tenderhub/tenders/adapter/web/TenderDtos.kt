package tenderhub.tenders.adapter.web

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import tenderhub.tenders.application.TenderView
import tenderhub.tenders.domain.CriteriaWeights
import tenderhub.tenders.domain.TenderCategory
import tenderhub.tenders.domain.TenderStatus
import tenderhub.tenders.domain.TenderTerms
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class WeightsDto(
    @field:Min(0) @field:Max(100) val price: Int,
    @field:Min(0) @field:Max(100) val delivery: Int,
    @field:Min(0) @field:Max(100) val warranty: Int,
) {
    fun toDomain() = CriteriaWeights(price, delivery, warranty)

    companion object {
        fun from(weights: CriteriaWeights) = WeightsDto(weights.price, weights.delivery, weights.warranty)
    }
}

data class TenderRequest(
    @field:NotBlank @field:Size(min = 3, max = 200) val title: String,
    @field:NotBlank @field:Size(max = 5000) val description: String,
    val category: TenderCategory,
    @field:DecimalMin(value = "0", inclusive = false) val maxPrice: BigDecimal,
    val deadline: Instant,
    @field:Valid val weights: WeightsDto,
) {
    fun toTerms() = TenderTerms(title.trim(), description.trim(), category, maxPrice, deadline, weights.toDomain())
}

data class ReasonRequest(
    @field:NotBlank @field:Size(max = 1000) val reason: String,
)

data class OptionalReasonRequest(
    @field:Size(max = 1000) val reason: String? = null,
)

data class TenderResponse(
    val id: UUID,
    val ownerId: UUID,
    val title: String,
    val description: String,
    val category: TenderCategory,
    val maxPrice: BigDecimal,
    val deadline: Instant,
    val weights: WeightsDto,
    val status: TenderStatus,
    val statusReason: String?,
    val activeBids: Int,
    val resultsAvailable: Boolean,
    val publishedAt: Instant?,
    val closedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(view: TenderView): TenderResponse {
            val t = view.tender
            return TenderResponse(
                id = t.id,
                ownerId = t.ownerId,
                title = t.terms.title,
                description = t.terms.description,
                category = t.terms.category,
                maxPrice = t.terms.maxPrice,
                deadline = t.terms.deadline,
                weights = WeightsDto.from(t.terms.weights),
                status = t.status,
                statusReason = t.statusReason,
                activeBids = view.activeBids,
                resultsAvailable = t.status.isClosed,
                publishedAt = t.publishedAt,
                closedAt = t.closedAt,
                createdAt = t.createdAt,
                updatedAt = t.updatedAt,
            )
        }
    }
}
