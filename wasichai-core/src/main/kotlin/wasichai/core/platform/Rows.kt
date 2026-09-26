package wasichai.core.platform

import io.r2dbc.spi.Row
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

// typed row getters. keeps driver quirks (timestamptz -> OffsetDateTime) out of repositories.
object Rows {
    fun uuid(
        row: Row,
        column: String
    ): UUID = row.get(column, UUID::class.java)!!

    fun uuidOrNull(
        row: Row,
        column: String
    ): UUID? = row.get(column, UUID::class.java)

    fun string(
        row: Row,
        column: String
    ): String = row.get(column, String::class.java)!!

    fun stringOrNull(
        row: Row,
        column: String
    ): String? = row.get(column, String::class.java)

    fun int(
        row: Row,
        column: String
    ): Int = (row.get(column) as Number).toInt()

    fun intOrNull(
        row: Row,
        column: String
    ): Int? = (row.get(column) as Number?)?.toInt()

    fun long(
        row: Row,
        column: String
    ): Long = (row.get(column) as Number).toLong()

    fun bool(
        row: Row,
        column: String
    ): Boolean = row.get(column) as Boolean

    fun instantOrNull(
        row: Row,
        column: String
    ): Instant? = row.get(column, OffsetDateTime::class.java)?.toInstant()
}
