package wasichai.core.identity

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

// everything the caller's roles say about one object (ADR-066), from RoleQueries.objectAccess
class ObjectAccess(
    val actions: Set<String>,
    val ownRecordsOnly: Boolean,
    val fields: FieldAccess
)

// field- and record-level rules. ADMIN bypasses everything, as does a role with no rule.
// both answers come from the request's one look at the object's rules (RoleQueries.objectAccess)
@Service
class AccessPolicy(
    private val roles: RoleQueries
) {
    // the constructor before ADR-066: kept so code that builds the policy itself keeps compiling
    constructor(db: DatabaseClient, schemas: WasichaiSchemas) : this(RoleQueries(db, schemas))

    suspend fun fieldAccess(
        user: AuthenticatedUser,
        objectId: UUID
    ): FieldAccess {
        if (user.isAdmin || user.roles.isEmpty()) return FieldAccess.FULL
        return roles.objectAccess(user.roles, user.organizationId, objectId).fields
    }

    // the user only sees their own records when every one of their roles says so
    suspend fun ownRecordsOnly(user: AuthenticatedUser): Boolean {
        if (user.isAdmin || user.roles.isEmpty()) return false
        return roles.ownRecordsOnly(user.roles, user.organizationId)
    }

    // null means "no owner filter". saves every caller the same if/else.
    suspend fun ownerFilter(user: AuthenticatedUser): UUID? = if (ownRecordsOnly(user)) user.userId else null

    companion object {
        // the owner rule as a subquery, where a read folds it in (RelationTargets, ADR-031 D30).
        // binds :organizationId and :roleNames (never empty). no row of those roles: null, read as false.
        internal fun ownRecordsOnlyQuery(schemas: WasichaiSchemas): String = RoleQueries.ownRecordsOnlyQuery(schemas)
    }
}
