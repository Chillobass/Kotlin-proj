package tenderhub.bidding.adapter.web

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tenderhub.bidding.application.BidService
import tenderhub.bidding.application.BidView
import tenderhub.bidding.application.RatingEntry
import tenderhub.bidding.application.ResultsService
import tenderhub.bidding.application.TenderClosingService
import tenderhub.bidding.application.TenderResults
import tenderhub.bidding.domain.BidOffer
import tenderhub.bidding.domain.BidStatus
import tenderhub.common.Actor
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BidRequest(
    @field:DecimalMin(value = "0", inclusive = false) val price: BigDecimal,
    @field:Min(1) val deliveryDays: Int,
    @field:Min(0) val warrantyMonths: Int,
    @field:Size(max = 2000) val comment: String? = null,
) {
    fun toOffer() = BidOffer(price, deliveryDays, warrantyMonths, comment?.trim()?.takeIf { it.isNotEmpty() })
}

data class BidResponse(
    val id: UUID,
    val tenderId: UUID,
    val tenderTitle: String,
    val tenderStatus: TenderStatus,
    val price: BigDecimal,
    val deliveryDays: Int,
    val warrantyMonths: Int,
    val comment: String?,
    val status: BidStatus,
    val submittedAt: Instant,
    val createdAt: Instant,
    val place: Int?,
    val score: BigDecimal?,
) {
    companion object {
        fun from(view: BidView): BidResponse {
            val bid = view.bid
            return BidResponse(
                id = bid.id,
                tenderId = bid.tenderId,
                tenderTitle = view.tenderTitle,
                tenderStatus = view.tenderStatus,
                price = bid.offer.price,
                deliveryDays = bid.offer.deliveryDays,
                warrantyMonths = bid.offer.warrantyMonths,
                comment = bid.offer.comment,
                status = bid.status,
                submittedAt = bid.submittedAt,
                createdAt = bid.createdAt,
                place = bid.result?.place,
                score = bid.result?.score,
            )
        }
    }
}

data class RatingEntryResponse(
    val place: Int,
    val score: BigDecimal,
    val bidId: UUID,
    val participantId: UUID,
    val participantName: String,
    val price: BigDecimal,
    val deliveryDays: Int,
    val warrantyMonths: Int,
    val comment: String?,
    val submittedAt: Instant,
) {
    companion object {
        fun from(entry: RatingEntry) = RatingEntryResponse(
            place = entry.place,
            score = entry.score,
            bidId = entry.bidId,
            participantId = entry.participantId,
            participantName = entry.participantName,
            price = entry.offer.price,
            deliveryDays = entry.offer.deliveryDays,
            warrantyMonths = entry.offer.warrantyMonths,
            comment = entry.offer.comment,
            submittedAt = entry.submittedAt,
        )
    }
}

data class TenderResultsResponse(
    val tenderId: UUID,
    val title: String,
    val status: TenderStatus,
    val winner: RatingEntryResponse?,
    val rating: List<RatingEntryResponse>,
) {
    companion object {
        fun from(results: TenderResults): TenderResultsResponse {
            val rating = results.rating.map(RatingEntryResponse::from)
            return TenderResultsResponse(results.tenderId, results.title, results.status, rating.firstOrNull(), rating)
        }
    }
}

data class ClosingRunResponse(val closed: Int)

@RestController
@RequestMapping("/api/v1")
class BidController(
    private val bidService: BidService,
    private val resultsService: ResultsService,
) {
    @PostMapping("/tenders/{tenderId}/bids")
    @ResponseStatus(HttpStatus.CREATED)
    fun submit(actor: Actor, @PathVariable tenderId: UUID, @Valid @RequestBody request: BidRequest): BidResponse =
        BidResponse.from(bidService.submit(actor, tenderId, request.toOffer()))

    @GetMapping("/tenders/{tenderId}/bids/my")
    fun myBidForTender(actor: Actor, @PathVariable tenderId: UUID): BidResponse =
        BidResponse.from(bidService.myBidForTender(actor, tenderId))

    @GetMapping("/tenders/{tenderId}/results")
    fun results(actor: Actor, @PathVariable tenderId: UUID): TenderResultsResponse =
        TenderResultsResponse.from(resultsService.results(actor, tenderId))

    @GetMapping("/bids/my")
    fun myBids(
        actor: Actor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<BidResponse> = bidService.myBids(actor, PageRequest(page, size)).map(BidResponse::from)
}

@RestController
@RequestMapping("/api/v1/admin/tenders")
class ClosingAdminController(private val closingService: TenderClosingService) {
    @PostMapping("/close-expired")
    fun closeExpired(actor: Actor): ClosingRunResponse = ClosingRunResponse(closingService.closeExpired(actor))
}
