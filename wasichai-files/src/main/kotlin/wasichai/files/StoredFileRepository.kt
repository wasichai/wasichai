package wasichai.files

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import wasichai.core.platform.Rows
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas
import wasichai.core.platform.bindNullable
import java.time.Duration
import java.time.Instant
import java.util.UUID

// a stored_files row: what was uploaded, by whom, for which object and field, and where its bytes are
data class StoredFile(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID,
    val fieldName: String,
    val objectKey: String,
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdBy: UUID?,
    val createdAt: Instant?
) {
    fun descriptor(): FileDescriptor = FileDescriptor(id, fileName, contentType, sizeBytes, sha256)
}

private const val COLUMNS =
    "id, organization_id, object_id, field_name, object_key, file_name, content_type, size_bytes, sha256, created_by, created_at"

// every query names its organization, except the cleanup's, which walks them one by one (ADR-0061)
@Repository
class StoredFileRepository(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(file: StoredFile): StoredFile =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.stored_files
                    (id, organization_id, object_id, field_name, object_key, file_name, content_type, size_bytes, sha256, created_by)
                VALUES (:id, :organizationId, :objectId, :fieldName, :objectKey, :fileName, :contentType, :sizeBytes, :sha256, :createdBy)
                RETURNING $COLUMNS
                """.trimIndent()
            ).bind("id", file.id)
            .bind("organizationId", file.organizationId)
            .bind("objectId", file.objectId)
            .bind("fieldName", file.fieldName)
            .bind("objectKey", file.objectKey)
            .bind("fileName", file.fileName)
            .bind("contentType", file.contentType)
            .bind("sizeBytes", file.sizeBytes)
            .bind("sha256", file.sha256)
            .bindNullable("createdBy", file.createdBy)
            .map { row, _ -> map(row) }
            .one()
            .awaitSingle()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): StoredFile? =
        db
            .sql("SELECT $COLUMNS FROM ${schemas.metadata}.stored_files WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .map { row, _ -> map(row) }
            .one()
            .awaitFirstOrNull()

    // the ids among [ids] that [userId] uploaded for this object and field within [maxAge]: the only new
    // values a write may give a file field (StoredFileGuard)
    suspend fun uploadedBy(
        organizationId: UUID,
        userId: UUID,
        objectId: UUID,
        fieldName: String,
        ids: Collection<UUID>,
        maxAge: Duration
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return db
            .sql(
                """
                SELECT id FROM ${schemas.metadata}.stored_files
                WHERE organization_id = :organizationId AND created_by = :userId AND object_id = :objectId
                  AND field_name = :fieldName AND id = ANY(:ids) AND created_at > now() - make_interval(secs => :maxAge)
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("userId", userId)
            .bind("objectId", objectId)
            .bind("fieldName", fieldName)
            .bind("ids", ids.toTypedArray())
            .bind("maxAge", maxAge.toMillis() / 1000.0)
            .map { row, _ -> Rows.uuid(row, "id") }
            .all()
            .asFlow()
            .toList()
            .toSet()
    }

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM ${schemas.metadata}.stored_files WHERE id = :id AND organization_id = :organizationId")
            .bind("id", id)
            .bind("organizationId", organizationId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    // the names of an object's file fields, for StoredFileGuard. empty for every other object.
    suspend fun fileFieldNames(objectId: UUID): Set<String> =
        db
            .sql("SELECT name FROM ${schemas.metadata}.custom_fields WHERE object_id = :objectId AND type IN ('FILE', 'IMAGE')")
            .bind("objectId", objectId)
            .map { row, _ -> row.get("name", String::class.java)!! }
            .all()
            .asFlow()
            .toList()
            .toSet()

    // ---- cleanup ----

    // every organization that holds a file older than the cutoff, deleted tenants included
    suspend fun organizationsWithFilesBefore(cutoff: Instant): List<UUID> =
        db
            .sql("SELECT DISTINCT organization_id FROM ${schemas.metadata}.stored_files WHERE created_at < :cutoff ORDER BY organization_id")
            .bind("cutoff", cutoff)
            .map { row, _ -> Rows.uuid(row, "organization_id") }
            .all()
            .asFlow()
            .toList()

    // the files of one organization older than the cutoff that no FILE or IMAGE column of its objects names.
    // table and column names come from the metadata and are quoted; values are bound.
    suspend fun orphans(
        organizationId: UUID,
        cutoff: Instant,
        limit: Int
    ): List<StoredFile> {
        val columns =
            db
                .sql(
                    """
                    SELECT o.physical_table, f.column_name
                    FROM ${schemas.metadata}.custom_fields f
                    JOIN ${schemas.metadata}.custom_objects o ON o.id = f.object_id
                    WHERE o.organization_id = :organizationId AND f.type IN ('FILE', 'IMAGE')
                    ORDER BY o.physical_table, f.column_name
                    """.trimIndent()
                ).bind("organizationId", organizationId)
                .map { row, _ -> row.get("physical_table", String::class.java)!! to row.get("column_name", String::class.java)!! }
                .all()
                .asFlow()
                .toList()
        val unreferenced =
            columns.joinToString("") { (table, column) ->
                "\n  AND NOT EXISTS (SELECT 1 FROM ${schemas.dataTable(table)} r " +
                    "WHERE r.${SqlIdentifier.quote(column)} = s.id AND r.organization_id = s.organization_id)"
            }
        return db
            .sql(
                """
                SELECT ${COLUMNS.split(", ").joinToString(", ") { "s.$it" }}
                FROM ${schemas.metadata}.stored_files s
                WHERE s.organization_id = :organizationId AND s.created_at < :cutoff$unreferenced
                ORDER BY s.created_at, s.id
                LIMIT :limit
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("cutoff", cutoff)
            .bind("limit", limit)
            .map { row, _ -> map(row) }
            .all()
            .asFlow()
            .toList()
    }

    private fun map(row: Row): StoredFile =
        StoredFile(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            objectId = Rows.uuid(row, "object_id"),
            fieldName = Rows.string(row, "field_name"),
            objectKey = Rows.string(row, "object_key"),
            fileName = Rows.string(row, "file_name"),
            contentType = Rows.string(row, "content_type"),
            sizeBytes = Rows.long(row, "size_bytes"),
            sha256 = Rows.string(row, "sha256"),
            createdBy = Rows.uuidOrNull(row, "created_by"),
            createdAt = Rows.instantOrNull(row, "created_at")
        )
}
