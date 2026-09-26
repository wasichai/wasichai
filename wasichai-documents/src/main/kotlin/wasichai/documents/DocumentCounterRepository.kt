package wasichai.documents

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

@Repository
class DocumentCounterRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    // one statement, so two callers cannot read the same number: the second waits on the row lock
    // the first took and then reads what the first left. it runs inside the caller's transaction,
    // so an issue that fails gives its number back instead of leaving a hole in the series.
    //
    // an upsert rather than a sequence for exactly that reason -- a sequence does not roll back.
    suspend fun next(
        documentTypeId: UUID,
        year: Int
    ): Int =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.document_counters (document_type_id, year, next)
                VALUES (:documentTypeId, :year, 2)
                ON CONFLICT (document_type_id, year)
                    DO UPDATE SET next = ${schemas.metadata}.document_counters.next + 1
                RETURNING next - 1 AS assigned
                """.trimIndent()
            ).bind("documentTypeId", documentTypeId)
            .bind("year", year)
            .map { row, _ -> (row.get("assigned") as Number).toInt() }
            .one()
            .awaitSingle()
}
