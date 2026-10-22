package tenderhub.tenders.adapter.persistence

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tenderhub.common.ConflictException
import tenderhub.common.Page
import tenderhub.common.PageRequest
import tenderhub.common.jdbc.enum
import tenderhub.common.jdbc.instant
import tenderhub.common.jdbc.instantOrNull
import tenderhub.common.jdbc.toDb
import tenderhub.common.jdbc.toDbOrNull
import tenderhub.common.jdbc.uuid
import tenderhub.common.jdbc.uuidOrNull
import tenderhub.tenders.application.TenderRepository
import tenderhub.tenders.application.TenderSearchFilter
import tenderhub.tenders.domain.CriteriaWeights
import tenderhub.tenders.domain.Tender
import tenderhub.tenders.domain.TenderStatus
import tenderhub.tenders.domain.TenderTerms
import java.time.Instant
import java.util.UUID

@Repository
class JdbcTenderRepository(private val jdbc: JdbcClient) : TenderRepository {
    private val mapper = RowMapper { rs, _ ->
        Tender.restore(
            id = rs.uuid("id"),
            ownerId = rs.uuid("owner_id"),
            terms = TenderTerms(
                title = rs.getString("title"),
                description = rs.getString("description"),
                category = rs.enum("category"),
                maxPrice = rs.getBigDecimal("max_price"),
                deadline = rs.instant("deadline"),
                weights = CriteriaWeights(
                    price = rs.getInt("weight_price"),
                    delivery = rs.getInt("weight_delivery"),
                    warranty = rs.getInt("weight_warranty"),
                ),
            ),
            status = rs.enum("status"),
            statusReason = rs.getString("status_reason"),
            moderatedBy = rs.uuidOrNull("moderated_by"),
            publishedAt = rs.instantOrNull("published_at"),
            closedAt = rs.instantOrNull("closed_at"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
            version = rs.getLong("version"),
        )
    }

    override fun insert(tender: Tender) {
        bindAll(
            jdbc.sql(
                """
                INSERT INTO tenders (id, owner_id, title, description, category, max_price, deadline,
                                     weight_price, weight_delivery, weight_warranty, status, status_reason,
                                     moderated_by, published_at, closed_at, created_at, updated_at, version)
                VALUES (:id, :ownerId, :title, :description, :category, :maxPrice, :deadline,
                        :weightPrice, :weightDelivery, :weightWarranty, :status, :statusReason,
                        :moderatedBy, :publishedAt, :closedAt, :createdAt, :updatedAt, :version)
                """.trimIndent(),
            ),
            tender,
        )
            .param("ownerId", tender.ownerId)
            .param("createdAt", tender.createdAt.toDb())
            .update()
    }

    override fun update(tender: Tender) {
        val updated = bindAll(
            jdbc.sql(
                """
                UPDATE tenders
                SET title = :title, description = :description, category = :category, max_price = :maxPrice,
                    deadline = :deadline, weight_price = :weightPrice, weight_delivery = :weightDelivery,
                    weight_warranty = :weightWarranty, status = :status, status_reason = :statusReason,
                    moderated_by = :moderatedBy, published_at = :publishedAt, closed_at = :closedAt,
                    updated_at = :updatedAt, version = version + 1
                WHERE id = :id AND version = :version
                """.trimIndent(),
            ),
            tender,
        ).update()
        if (updated == 0) {
            throw ConflictException("CONCURRENT_MODIFICATION", "Тендер был изменён параллельно, повторите запрос")
        }
        tender.incrementVersion()
    }

    override fun findById(id: UUID): Tender? = findOne("SELECT * FROM tenders WHERE id = :id", id)

    override fun findByIdForUpdate(id: UUID): Tender? = findOne("SELECT * FROM tenders WHERE id = :id FOR UPDATE", id)

    override fun findByIdForShare(id: UUID): Tender? = findOne("SELECT * FROM tenders WHERE id = :id FOR SHARE", id)

    override fun lockExpiredOpen(id: UUID, now: Instant): Tender? =
        jdbc.sql(
            """
            SELECT * FROM tenders
            WHERE id = :id AND status = 'OPEN' AND deadline <= :now
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
        )
            .param("id", id)
            .param("now", now.toDb())
            .query(mapper)
            .optional()
            .orElse(null)

    override fun findExpiredOpenIds(now: Instant, limit: Int): List<UUID> =
        jdbc.sql("SELECT id FROM tenders WHERE status = 'OPEN' AND deadline <= :now ORDER BY deadline LIMIT :limit")
            .param("now", now.toDb())
            .param("limit", limit)
            .query(RowMapper { rs, _ -> rs.uuid("id") })
            .list()

    override fun search(filter: TenderSearchFilter, page: PageRequest): Page<Tender> {
        val conditions = mutableListOf("status = :status", "published_at IS NOT NULL")
        val params = mutableMapOf<String, Any>("status" to filter.status.name)
        filter.category?.let { conditions += "category = :category"; params["category"] = it.name }
        filter.minPrice?.let { conditions += "max_price >= :minPrice"; params["minPrice"] = it }
        filter.maxPrice?.let { conditions += "max_price <= :maxPrice"; params["maxPrice"] = it }
        filter.deadlineFrom?.let { conditions += "deadline >= :deadlineFrom"; params["deadlineFrom"] = it.toDb() }
        filter.deadlineTo?.let { conditions += "deadline <= :deadlineTo"; params["deadlineTo"] = it.toDb() }
        filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            conditions += "(title ILIKE :text OR description ILIKE :text)"
            params["text"] = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        val where = conditions.joinToString(" AND ")
        val order = if (filter.status == TenderStatus.OPEN) "deadline ASC, id" else "updated_at DESC, id"
        return fetchPage("SELECT * FROM tenders WHERE $where ORDER BY $order", "SELECT count(*) FROM tenders WHERE $where", params, page)
    }

    override fun findByOwner(ownerId: UUID, page: PageRequest): Page<Tender> =
        fetchPage(
            "SELECT * FROM tenders WHERE owner_id = :ownerId ORDER BY created_at DESC, id",
            "SELECT count(*) FROM tenders WHERE owner_id = :ownerId",
            mapOf("ownerId" to ownerId),
            page,
        )

    override fun findByStatus(status: TenderStatus, page: PageRequest): Page<Tender> =
        fetchPage(
            "SELECT * FROM tenders WHERE status = :status ORDER BY updated_at ASC, id",
            "SELECT count(*) FROM tenders WHERE status = :status",
            mapOf("status" to status.name),
            page,
        )

    override fun countByOwnerAndStatus(ownerId: UUID, status: TenderStatus): Int =
        jdbc.sql("SELECT count(*) FROM tenders WHERE owner_id = :ownerId AND status = :status")
            .param("ownerId", ownerId)
            .param("status", status.name)
            .query(Int::class.javaObjectType)
            .single()

    override fun findIdsByOwnerAndStatusIn(ownerId: UUID, statuses: Set<TenderStatus>): List<UUID> =
        jdbc.sql("SELECT id FROM tenders WHERE owner_id = :ownerId AND status IN (:statuses) ORDER BY id")
            .param("ownerId", ownerId)
            .param("statuses", statuses.map { it.name })
            .query(RowMapper { rs, _ -> rs.uuid("id") })
            .list()

    private fun findOne(sql: String, id: UUID): Tender? =
        jdbc.sql(sql).param("id", id).query(mapper).optional().orElse(null)

    private fun fetchPage(sql: String, countSql: String, params: Map<String, Any>, page: PageRequest): Page<Tender> {
        val items = jdbc.sql("$sql LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", page.size)
            .param("offset", page.offset)
            .query(mapper)
            .list()
        val total = jdbc.sql(countSql).params(params).query(Long::class.javaObjectType).single()
        return Page.of(items, page, total)
    }

    private fun bindAll(spec: JdbcClient.StatementSpec, tender: Tender): JdbcClient.StatementSpec {
        val terms = tender.terms
        return spec
            .param("id", tender.id)
            .param("title", terms.title)
            .param("description", terms.description)
            .param("category", terms.category.name)
            .param("maxPrice", terms.maxPrice)
            .param("deadline", terms.deadline.toDb())
            .param("weightPrice", terms.weights.price)
            .param("weightDelivery", terms.weights.delivery)
            .param("weightWarranty", terms.weights.warranty)
            .param("status", tender.status.name)
            .param("statusReason", tender.statusReason)
            .param("moderatedBy", tender.moderatedBy)
            .param("publishedAt", tender.publishedAt.toDbOrNull())
            .param("closedAt", tender.closedAt.toDbOrNull())
            .param("updatedAt", tender.updatedAt.toDb())
            .param("version", tender.version)
    }
}
