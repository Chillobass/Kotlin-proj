package tenderhub.common.jdbc

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)

fun Instant?.toDbOrNull(): OffsetDateTime? = this?.toDb()

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

inline fun <reified E : Enum<E>> ResultSet.enum(column: String): E = enumValueOf(getString(column))
