package tenderhub.bidding.adapter.persistence

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tenderhub.bidding.application.BidRepository
import tenderhub.bidding.domain.Bid
import tenderhub.bidding.domain.BidOffer
import tenderhub.bidding.domain.BidResult
import tenderhub.common.ConflictException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.jdbc.enum
import tenderhub.common.jdbc.instant
import tenderhub.common.jdbc.intOrNull
import tenderhub.common.jdbc.toDb
import tenderhub.common.jdbc.uuid
import java.util.UUID

@Repository
class JdbcBidRepository(private val jdbc: JdbcClient) : BidRepository {
    private val mapper = RowMapper { rs, _ ->
        val place = rs.intOrNull("final_place")
        Bid.restore(
            id = rs.uuid("id"),
            tenderId = rs.uuid("tender_id"),
            participantId = rs.uuid("participant_id"),
            offer = BidOffer(
                price = rs.getBigDecimal("price"),
                deliveryDays = rs.getInt("delivery_days"),
                warrantyMonths = rs.getInt("warranty_months"),
                comment = rs.getString("comment"),
            ),
            status = rs.enum("status"),
            submittedAt = rs.instant("submitted_at"),
            createdAt = rs.instant("created_at"),
            result = place?.let { BidResult(it, rs.getBigDecimal("final_score")) },
            version = rs.getLong("version"),
        )
    }

    override fun insert(bid: Bid) {
        try {
            bindMutable(
                jdbc.sql(
                    """
                    INSERT INTO bids (id, tender_id, participant_id, price, delivery_days, warranty_months, comment,
                                      status, submitted_at, created_at, final_place, final_score, version)
                    VALUES (:id, :tenderId, :participantId, :price, :deliveryDays, :warrantyMonths, :comment,
                            :status, :submittedAt, :createdAt, :finalPlace, :finalScore, :version)
                    """.trimIndent(),
                ),
                bid,
            )
                .param("tenderId", bid.tenderId)
                .param("participantId", bid.participantId)
                .param("createdAt", bid.createdAt.toDb())
                .update()
        } catch (e: DuplicateKeyException) {
            throw ConflictException("BID_ALREADY_EXISTS", "Вы уже подали заявку на этот тендер, изменить её нельзя")
        }
    }

    override fun update(bid: Bid) {
        val updated = bindMutable(
            jdbc.sql(
                """
                UPDATE bids
                SET status = :status, final_place = :finalPlace, final_score = :finalScore, version = version + 1
                WHERE id = :id AND version = :version
                """.trimIndent(),
            ),
            bid,
        ).update()
        if (updated == 0) {
            throw ConflictException("CONCURRENT_MODIFICATION", "Заявка была изменена параллельно, повторите запрос")
        }
        bid.incrementVersion()
    }

    override fun findById(id: UUID): Bid? =
        jdbc.sql("SELECT * FROM bids WHERE id = :id").param("id", id).query(mapper).optional().orElse(null)

    override fun findByIdForUpdate(id: UUID): Bid? =
        jdbc.sql("SELECT * FROM bids WHERE id = :id FOR UPDATE").param("id", id).query(mapper).optional().orElse(null)

    override fun findActive(tenderId: UUID, participantId: UUID): Bid? =
        jdbc.sql("SELECT * FROM bids WHERE tender_id = :tenderId AND participant_id = :participantId AND status = 'ACTIVE'")
            .param("tenderId", tenderId)
            .param("participantId", participantId)
            .query(mapper)
            .optional()
            .orElse(null)

    override fun findActiveByTender(tenderId: UUID): List<Bid> =
        jdbc.sql("SELECT * FROM bids WHERE tender_id = :tenderId AND status = 'ACTIVE' ORDER BY submitted_at, id")
            .param("tenderId", tenderId)
            .query(mapper)
            .list()

    override fun findRanked(tenderId: UUID): List<Bid> =
        jdbc.sql("SELECT * FROM bids WHERE tender_id = :tenderId AND final_place IS NOT NULL ORDER BY final_place")
            .param("tenderId", tenderId)
            .query(mapper)
            .list()

    override fun findByParticipant(participantId: UUID, page: PageRequest): Page<Bid> {
        val items = jdbc.sql(
            "SELECT * FROM bids WHERE participant_id = :participantId ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
        )
            .param("participantId", participantId)
            .param("limit", page.size)
            .param("offset", page.offset)
            .query(mapper)
            .list()
        val total = jdbc.sql("SELECT count(*) FROM bids WHERE participant_id = :participantId")
            .param("participantId", participantId)
            .query(Long::class.javaObjectType)
            .single()
        return Page.of(items, page, total)
    }

    override fun findActiveIdsByParticipant(participantId: UUID): List<UUID> =
        jdbc.sql("SELECT id FROM bids WHERE participant_id = :participantId AND status = 'ACTIVE' AND final_place IS NULL")
            .param("participantId", participantId)
            .query(RowMapper { rs, _ -> rs.uuid("id") })
            .list()

    override fun countActive(tenderId: UUID): Int =
        jdbc.sql("SELECT count(*) FROM bids WHERE tender_id = :tenderId AND status = 'ACTIVE'")
            .param("tenderId", tenderId)
            .query(Int::class.javaObjectType)
            .single()

    override fun countActive(tenderIds: Collection<UUID>): Map<UUID, Int> =
        jdbc.sql(
            "SELECT tender_id, count(*) AS cnt FROM bids WHERE tender_id IN (:ids) AND status = 'ACTIVE' GROUP BY tender_id",
        )
            .param("ids", tenderIds.toList())
            .query(RowMapper { rs, _ -> rs.uuid("tender_id") to rs.getInt("cnt") })
            .list()
            .toMap()

    private fun bindMutable(spec: JdbcClient.StatementSpec, bid: Bid): JdbcClient.StatementSpec = spec
        .param("id", bid.id)
        .param("price", bid.offer.price)
        .param("deliveryDays", bid.offer.deliveryDays)
        .param("warrantyMonths", bid.offer.warrantyMonths)
        .param("comment", bid.offer.comment)
        .param("status", bid.status.name)
        .param("submittedAt", bid.submittedAt.toDb())
        .param("finalPlace", bid.result?.place)
        .param("finalScore", bid.result?.score)
        .param("version", bid.version)
}
