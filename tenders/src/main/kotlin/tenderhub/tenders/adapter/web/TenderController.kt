package tenderhub.tenders.adapter.web

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tenderhub.common.Actor
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.tenders.application.ModerationService
import tenderhub.tenders.application.TenderSearchFilter
import tenderhub.tenders.application.TenderService
import tenderhub.tenders.domain.TenderCategory
import tenderhub.tenders.domain.TenderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/tenders")
class TenderController(private val tenderService: TenderService) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(actor: Actor, @Valid @RequestBody request: TenderRequest): TenderResponse =
        TenderResponse.from(tenderService.create(actor, request.toTerms()))

    @GetMapping
    fun search(
        @RequestParam(defaultValue = "OPEN") status: TenderStatus,
        @RequestParam(required = false) category: TenderCategory?,
        @RequestParam(required = false) minPrice: BigDecimal?,
        @RequestParam(required = false) maxPrice: BigDecimal?,
        @RequestParam(required = false) deadlineFrom: Instant?,
        @RequestParam(required = false) deadlineTo: Instant?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<TenderResponse> {
        val filter = TenderSearchFilter(status, category, minPrice, maxPrice, deadlineFrom, deadlineTo, q)
        return tenderService.search(filter, PageRequest(page, size)).map(TenderResponse::from)
    }

    @GetMapping("/my")
    fun mine(
        actor: Actor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<TenderResponse> = tenderService.mine(actor, PageRequest(page, size)).map(TenderResponse::from)

    @GetMapping("/{id}")
    fun get(actor: Actor, @PathVariable id: UUID): TenderResponse = TenderResponse.from(tenderService.get(actor, id))

    @PutMapping("/{id}")
    fun update(actor: Actor, @PathVariable id: UUID, @Valid @RequestBody request: TenderRequest): TenderResponse =
        TenderResponse.from(tenderService.update(actor, id, request.toTerms()))

    @PostMapping("/{id}/resubmit")
    fun resubmit(actor: Actor, @PathVariable id: UUID): TenderResponse =
        TenderResponse.from(tenderService.resubmit(actor, id))

    @PostMapping("/{id}/cancel")
    fun cancel(
        actor: Actor,
        @PathVariable id: UUID,
        @Valid @RequestBody(required = false) request: OptionalReasonRequest?,
    ): TenderResponse = TenderResponse.from(tenderService.cancel(actor, id, request?.reason))
}

@RestController
@RequestMapping("/api/v1/admin/tenders")
class ModerationController(private val moderationService: ModerationService) {
    @GetMapping("/review-queue")
    fun reviewQueue(
        actor: Actor,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): Page<TenderResponse> = moderationService.reviewQueue(actor, PageRequest(page, size)).map(TenderResponse::from)

    @PostMapping("/{id}/approve")
    fun approve(actor: Actor, @PathVariable id: UUID): TenderResponse =
        TenderResponse.from(moderationService.approve(actor, id))

    @PostMapping("/{id}/reject")
    fun reject(actor: Actor, @PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest): TenderResponse =
        TenderResponse.from(moderationService.reject(actor, id, request.reason))

    @PostMapping("/{id}/cancel")
    fun cancel(actor: Actor, @PathVariable id: UUID, @Valid @RequestBody request: ReasonRequest): TenderResponse =
        TenderResponse.from(moderationService.cancel(actor, id, request.reason))
}
