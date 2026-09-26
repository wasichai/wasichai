package wasichai.documents

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import wasichai.core.platform.Rows
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// jsonb travels as text over r2dbc: cast on the way in, ::text on the way out.
private const val DOCUMENT_COLUMNS =
    "id, organization_id, document_type_id, object_id, record_id, number, year, sequence, status, " +
        "snapshot::text AS snapshot, issued_at, issued_by"

@Repository
class DocumentRepository(
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper,
    private val schemas: WasichaiSchemas
) {
    suspend fun insert(document: Document): Document =
        db
            .sql(
                """
                INSERT INTO ${schemas.metadata}.documents
                    (id, organization_id, document_type_id, object_id, record_id, number, year, sequence, status, snapshot, issued_by)
                VALUES (:id, :organizationId, :documentTypeId, :objectId, :recordId, :number, :year, :sequence, :status,
                        CAST(:snapshot AS jsonb), :issuedBy)
                RETURNING $DOCUMENT_COLUMNS
                """.trimIndent()
            ).bind("id", document.id)
            .bind("organizationId", document.organizationId)
            .bind("documentTypeId", document.documentTypeId)
            .bind("objectId", document.objectId)
            .bind("recordId", document.recordId)
            .bind("number", document.number)
            .bind("year", document.year)
            .bind("sequence", document.sequence)
            .bind("status", document.status.name)
            .bind("snapshot", objectMapper.writeValueAsString(document.snapshot))
            .let { spec -> document.issuedBy?.let { spec.bind("issuedBy", it) } ?: spec.bindNull("issuedBy", UUID::class.java) }
            .map(::map)
            .one()
            .awaitSingle()

    // two issues of one type on one record must not race: only one of them can end up valid, so
    // without this the loser dies on the partial unique index and a double-click becomes an error
    // instead of what the admin meant -- issue again, archiving the last one.
    //
    // an advisory lock rather than SELECT ... FOR UPDATE because the first issue has no row to
    // lock. it is held by the transaction and let go on commit or rollback, either way.
    suspend fun lockRecordType(
        documentTypeId: UUID,
        recordId: UUID
    ) {
        db
            .sql("SELECT pg_advisory_xact_lock(hashtext(:key))")
            .bind("key", "$documentTypeId:$recordId")
            .fetch()
            .one()
            .awaitSingle()
    }

    // what the record had until now. archiving runs before the insert, so the partial unique index
    // never sees two valid documents of one type even for an instant.
    suspend fun archiveValid(
        documentTypeId: UUID,
        recordId: UUID
    ): Long =
        db
            .sql(
                """
                UPDATE ${schemas.metadata}.documents SET status = 'ARCHIVED'
                WHERE document_type_id = :documentTypeId AND record_id = :recordId AND status = 'VALID'
                """.trimIndent()
            ).bind("documentTypeId", documentTypeId)
            .bind("recordId", recordId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    suspend fun findForRecord(
        organizationId: UUID,
        objectId: UUID,
        recordId: UUID
    ): List<Document> =
        db
            .sql(
                """
                SELECT $DOCUMENT_COLUMNS FROM ${schemas.metadata}.documents
                WHERE organization_id = :organizationId AND object_id = :objectId AND record_id = :recordId
                ORDER BY issued_at DESC
                """.trimIndent()
            ).bind("organizationId", organizationId)
            .bind("objectId", objectId)
            .bind("recordId", recordId)
            .map(::map)
            .all()
            .asFlow()
            .toList()

    suspend fun findById(
        organizationId: UUID,
        id: UUID
    ): Document? =
        db
            .sql("SELECT $DOCUMENT_COLUMNS FROM ${schemas.metadata}.documents WHERE organization_id = :organizationId AND id = :id")
            .bind("organizationId", organizationId)
            .bind("id", id)
            .map(::map)
            .one()
            .awaitFirstOrNull()

    suspend fun countForType(documentTypeId: UUID): Long =
        db
            .sql("SELECT count(*) AS total FROM ${schemas.metadata}.documents WHERE document_type_id = :documentTypeId")
            .bind("documentTypeId", documentTypeId)
            .map { row, _ -> (row.get("total") as Number).toLong() }
            .one()
            .awaitSingle()

    private fun map(
        row: Row,
        metadata: RowMetadata
    ): Document =
        Document(
            id = Rows.uuid(row, "id"),
            organizationId = Rows.uuid(row, "organization_id"),
            documentTypeId = Rows.uuid(row, "document_type_id"),
            objectId = Rows.uuid(row, "object_id"),
            recordId = Rows.uuid(row, "record_id"),
            number = Rows.string(row, "number"),
            year = (row.get("year") as Number).toInt(),
            sequence = (row.get("sequence") as Number).toInt(),
            status = DocumentStatus.valueOf(Rows.string(row, "status")),
            snapshot = objectMapper.readValue(Rows.string(row, "snapshot"), DocumentSnapshot::class.java),
            issuedAt = Rows.instantOrNull(row, "issued_at"),
            issuedBy = Rows.uuidOrNull(row, "issued_by")
        )
}
