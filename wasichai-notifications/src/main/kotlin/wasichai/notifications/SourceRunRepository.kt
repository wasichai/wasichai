package wasichai.notifications

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant

// notification_source_runs: one row per loop item, every replica reads it. not per organization: a run covers them all.
internal class SourceRunRepository(
    private val db: DatabaseClient,
    schemas: WasichaiSchemas
) : SourceRuns {
    private val m = schemas.metadata

    override suspend fun lastRun(key: String): Instant? =
        db
            .sql("SELECT last_run_at FROM $m.notification_source_runs WHERE source = :source")
            .bind("source", key)
            .map { row, _ -> Rows.instantOrNull(row, "last_run_at")!! }
            .one()
            .awaitFirstOrNull()

    override suspend fun markRun(
        key: String,
        at: Instant
    ) {
        db
            .sql(
                "INSERT INTO $m.notification_source_runs (source, last_run_at) VALUES (:source, :at) " +
                    "ON CONFLICT (source) DO UPDATE SET last_run_at = EXCLUDED.last_run_at"
            ).bind("source", key)
            .bind("at", at.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
