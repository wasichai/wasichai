package wasichai.core.identity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

// what one caller may do with the fields of one object.
// restriction model: a field nobody wrote a rule for is fully accessible.
class FieldAccess(
    private val read: Map<UUID, Boolean>,
    private val write: Map<UUID, Boolean>
) {
    fun canRead(fieldId: UUID): Boolean = read[fieldId] ?: true

    fun canWrite(fieldId: UUID): Boolean = write[fieldId] ?: true

    // nothing to filter: callers skip the copying entirely
    val unrestricted: Boolean get() = read.isEmpty() && write.isEmpty()

    companion object {
        val FULL = FieldAccess(emptyMap(), emptyMap())
    }
}

// field- and record-level rules. ADMIN bypasses everything, as does a role with no rule.
@Service
class AccessPolicy(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas
) {
    suspend fun fieldAccess(
        user: AuthenticatedUser,
        objectId: UUID
    ): FieldAccess {
        if (user.isAdmin || user.roles.isEmpty()) return FieldAccess.FULL
        val read = mutableMapOf<UUID, Boolean>()
        val write = mutableMapOf<UUID, Boolean>()
        // one role without a rule already grants the field: the permissive union wins
        db
            .sql(
                """
                WITH my_roles AS (
                    SELECT id FROM ${schemas.metadata}.roles
                    WHERE organization_id = :organizationId AND name IN (:roleNames)
                )
                SELECT f.id AS field_id,
                       bool_or(COALESCE(fp.can_read, true)) AS can_read,
                       bool_or(COALESCE(fp.can_write, true)) AS can_write
                FROM my_roles mr
                CROSS JOIN ${schemas.metadata}.custom_fields f
                LEFT JOIN ${schemas.metadata}.field_permissions fp ON fp.role_id = mr.id AND fp.field_id = f.id
                WHERE f.object_id = :objectId
                GROUP BY f.id
                HAVING NOT (bool_or(COALESCE(fp.can_read, true)) AND bool_or(COALESCE(fp.can_write, true)))
                """.trimIndent()
            ).bind("organizationId", user.organizationId)
            .bind("roleNames", user.roles)
            .bind("objectId", objectId)
            .map { row, _ ->
                val id = row.get("field_id", UUID::class.java)!!
                read[id] = row.get("can_read") as Boolean
                write[id] = row.get("can_write") as Boolean
            }.all()
            .asFlow()
            .toList()
        if (read.isEmpty()) return FieldAccess.FULL
        return FieldAccess(read, write)
    }

    // the user only sees their own records when every one of their roles says so
    suspend fun ownRecordsOnly(user: AuthenticatedUser): Boolean {
        if (user.isAdmin || user.roles.isEmpty()) return false
        return db
            .sql(ownRecordsOnlyQuery(schemas))
            .bind("organizationId", user.organizationId)
            .bind("roleNames", user.roles)
            .map { row, _ -> row.get("restricted") as Boolean? ?: false }
            .one()
            .awaitFirstOrNull() ?: false
    }

    // null means "no owner filter". saves every caller the same if/else.
    suspend fun ownerFilter(user: AuthenticatedUser): UUID? = if (ownRecordsOnly(user)) user.userId else null

    companion object {
        // the owner rule: true when every role of the caller says own records only. a query of its own
        // here, a subquery where a read folds it in (RelationTargets, ADR-031 D30).
        // binds :organizationId and :roleNames (never empty). no row of those roles: null, read as false.
        internal fun ownRecordsOnlyQuery(schemas: WasichaiSchemas): String =
            """
            SELECT bool_and(own_records_only) AS restricted
            FROM ${schemas.metadata}.roles
            WHERE organization_id = :organizationId AND name IN (:roleNames)
            """.trimIndent()
    }
}
