package tenderhub.tenders.domain

import tenderhub.common.Actor
import tenderhub.common.BusinessRuleException
import tenderhub.common.ConflictException
import tenderhub.common.ForbiddenException
import tenderhub.common.validate
import java.time.Instant
import java.util.UUID

/*
ON_REVIEW -> OPEN | REJECTED | CANCELLED
REJECTED  -> ON_REVIEW (resubmit) | CANCELLED
OPEN      -> COMPLETED | FAILED (deadline reached) | CANCELLED
*/
class Tender private constructor(
    val id: UUID,
    val ownerId: UUID,
    terms: TenderTerms,
    status: TenderStatus,
    statusReason: String?,
    moderatedBy: UUID?,
    publishedAt: Instant?,
    closedAt: Instant?,
    val createdAt: Instant,
    updatedAt: Instant,
    version: Long,
) {
    var terms: TenderTerms = terms
        private set
    var status: TenderStatus = status
        private set

    var statusReason: String? = statusReason
        private set
    var moderatedBy: UUID? = moderatedBy
        private set
    var publishedAt: Instant? = publishedAt
        private set
    var closedAt: Instant? = closedAt
        private set
    var updatedAt: Instant = updatedAt
        private set

    var version: Long = version
        private set

    val isPublished: Boolean get() = publishedAt != null

    fun isOwnedBy(actor: Actor) = actor.userId == ownerId

    fun isVisibleTo(actor: Actor): Boolean = isPublished || isOwnedBy(actor) || actor.isAdmin

    fun edit(newTerms: TenderTerms, policy: TenderPolicy, now: Instant) {
        requireStatus(setOf(TenderStatus.ON_REVIEW, TenderStatus.REJECTED), "TENDER_NOT_EDITABLE") {
            "Редактировать можно только тендер на модерации или отклонённый (сейчас $status)"
        }
        policy.checkDeadline(newTerms.deadline, now)
        terms = newTerms
        updatedAt = now
    }

    fun resubmit(policy: TenderPolicy, now: Instant) {
        requireStatus(setOf(TenderStatus.REJECTED), "TENDER_NOT_REJECTED") {
            "Повторно отправить можно только отклонённый тендер"
        }
        policy.checkDeadline(terms.deadline, now)
        status = TenderStatus.ON_REVIEW
        statusReason = null
        updatedAt = now
    }

    fun approve(adminId: UUID, policy: TenderPolicy, now: Instant) {
        requireStatus(setOf(TenderStatus.ON_REVIEW), "TENDER_NOT_ON_REVIEW") { "Тендер не находится на модерации" }
        if (terms.deadline.isBefore(now.plus(policy.minOpenDuration))) {
            throw BusinessRuleException(
                "DEADLINE_TOO_CLOSE",
                "До окончания приёма заявок осталось меньше ${policy.minOpenDuration.pretty()}: " +
                    "отклоните тендер, чтобы владелец перенёс срок",
            )
        }
        status = TenderStatus.OPEN
        moderatedBy = adminId
        publishedAt = now
        updatedAt = now
    }

    fun reject(adminId: UUID, reason: String, now: Instant) {
        requireStatus(setOf(TenderStatus.ON_REVIEW), "TENDER_NOT_ON_REVIEW") { "Тендер не находится на модерации" }
        val validReason = requireReason(reason)
        status = TenderStatus.REJECTED
        statusReason = validReason
        moderatedBy = adminId
        updatedAt = now
    }

    fun cancelByOwner(reason: String?, hasActiveBids: Boolean, now: Instant) {
        requireStatus(CANCELLABLE, "TENDER_NOT_CANCELLABLE") { "Тендер в статусе $status нельзя отменить" }
        if (status == TenderStatus.OPEN) {
            requireBeforeDeadline(now)
            if (hasActiveBids) {
                throw BusinessRuleException(
                    "TENDER_HAS_BIDS",
                    "На тендер уже поданы заявки: отменить его может только администратор",
                )
            }
        }
        doCancel(reason?.trim()?.takeIf { it.isNotEmpty() } ?: "Отменён владельцем", now)
    }

    fun cancelByAdmin(reason: String, now: Instant) {
        requireStatus(CANCELLABLE, "TENDER_NOT_CANCELLABLE") { "Тендер в статусе $status нельзя отменить" }
        if (status == TenderStatus.OPEN) requireBeforeDeadline(now)
        doCancel(requireReason(reason), now)
    }

    fun close(hasBids: Boolean, now: Instant) {
        requireStatus(setOf(TenderStatus.OPEN), "TENDER_NOT_OPEN") { "Закрыть можно только открытый тендер" }
        if (now.isBefore(terms.deadline)) {
            throw ConflictException("DEADLINE_NOT_REACHED", "Срок приёма заявок ещё не истёк")
        }
        status = if (hasBids) TenderStatus.COMPLETED else TenderStatus.FAILED
        closedAt = now
        updatedAt = now
    }

    fun requireOwner(actor: Actor) {
        if (!isOwnedBy(actor)) throw ForbiddenException("NOT_TENDER_OWNER", "Действие доступно только владельцу тендера")
    }

    internal fun incrementVersion() {
        version++
    }

    private fun doCancel(reason: String, now: Instant) {
        status = TenderStatus.CANCELLED
        statusReason = reason
        updatedAt = now
    }

    private fun requireBeforeDeadline(now: Instant) {
        if (!now.isBefore(terms.deadline)) {
            throw ConflictException("TENDER_DEADLINE_PASSED", "Срок приёма заявок истёк, тендер закрывается")
        }
    }

    private inline fun requireStatus(allowed: Set<TenderStatus>, code: String, message: () -> String) {
        if (status !in allowed) throw ConflictException(code, message())
    }

    private fun requireReason(reason: String): String {
        val trimmed = reason.trim()
        validate(trimmed.isNotEmpty(), "reason", "REASON_REQUIRED") { "Укажите причину" }
        validate(trimmed.length <= 1000, "reason", "REASON_TOO_LONG") { "Причина не длиннее 1000 символов" }
        return trimmed
    }

    companion object {
        private val CANCELLABLE = setOf(TenderStatus.ON_REVIEW, TenderStatus.REJECTED, TenderStatus.OPEN)

        fun submit(id: UUID, ownerId: UUID, terms: TenderTerms, policy: TenderPolicy, now: Instant): Tender {
            policy.checkDeadline(terms.deadline, now)
            return Tender(
                id = id,
                ownerId = ownerId,
                terms = terms,
                status = TenderStatus.ON_REVIEW,
                statusReason = null,
                moderatedBy = null,
                publishedAt = null,
                closedAt = null,
                createdAt = now,
                updatedAt = now,
                version = 0,
            )
        }

        fun restore(
            id: UUID,
            ownerId: UUID,
            terms: TenderTerms,
            status: TenderStatus,
            statusReason: String?,
            moderatedBy: UUID?,
            publishedAt: Instant?,
            closedAt: Instant?,
            createdAt: Instant,
            updatedAt: Instant,
            version: Long,
        ) = Tender(
            id, ownerId, terms, status, statusReason, moderatedBy, publishedAt, closedAt, createdAt, updatedAt, version,
        )
    }
}
