package wasichai.gis

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import wasichai.core.common.Actions
import wasichai.core.common.ValidationException
import wasichai.core.identity.AccessPolicy
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.readableBy
import wasichai.core.platform.SqlIdentifier
import wasichai.core.platform.WasichaiSchemas

// the classes of a style come from the data, once, here: the map, its legend and the SLD all read the same breaks.
@Service
class StyleClassService(
    private val db: DatabaseClient,
    private val schemas: WasichaiSchemas,
    private val metadata: MetadataService,
    private val access: AccessPolicy,
    private val currentUser: CurrentUser
) {
    suspend fun classes(
        objectName: String,
        style: AttributeStyle
    ): StyleClasses {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName.trim().lowercase())
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return classes(user, definition, style)
    }

    // the caller already loaded the definition and checked its own permission
    suspend fun classes(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        style: AttributeStyle
    ): StyleClasses {
        val field = validate(user, definition, style)
        val classes =
            when (style.type) {
                StyleType.CATEGORIES -> StyleClassifier.categories(categories(user, definition, field), style.palette)
                StyleType.QUANTILES -> StyleClassifier.quantiles(breaks(user, definition, field, style.classes ?: DEFAULT_CLASSES), style.palette)
            }
        return StyleClasses(style.by, style.type, classes)
    }

    // a field the caller may not read cannot colour their map either
    suspend fun validate(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        style: AttributeStyle
    ): CustomField = style.validate(definition.readableBy(access.fieldAccess(user, definition.obj.id)))

    private suspend fun categories(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        field: CustomField
    ): List<Any> {
        // the options are the categories, in their order, whether a record uses them yet or not
        if (field.type == FieldType.ENUM) field.enumOptions?.let { return it }
        if (field.type == FieldType.BOOLEAN) return listOf(true, false)
        val column = SqlIdentifier.quote(field.columnName)
        val values =
            db
                .sql(
                    "SELECT DISTINCT $column AS value FROM ${schemas.dataTable(definition.obj.physicalTable)} " +
                        "WHERE organization_id = :organizationId AND $column IS NOT NULL ORDER BY 1 LIMIT ${MAX_CATEGORIES + 1}"
                ).bind("organizationId", user.organizationId)
                .map { row, _ -> row.get("value") as Any }
                .all()
                .asFlow()
                .toList()
        if (values.size > MAX_CATEGORIES) {
            throw ValidationException(
                "Field '${field.name}' has too many categories",
                "style.by",
                "at most $MAX_CATEGORIES distinct values can be styled by category; use quantiles or an ENUM"
            )
        }
        return values
    }

    private suspend fun breaks(
        user: AuthenticatedUser,
        definition: ObjectDefinition,
        field: CustomField,
        classes: Int
    ): List<Double> {
        val column = SqlIdentifier.quote(field.columnName)
        // no rows: the aggregate answers null, coalesced to no breaks and so no classes
        val breaks =
            db
                .sql(
                    "SELECT COALESCE(percentile_cont(:fractions) WITHIN GROUP (ORDER BY $column), ARRAY[]::double precision[]) AS breaks " +
                        "FROM ${schemas.dataTable(definition.obj.physicalTable)} WHERE organization_id = :organizationId AND $column IS NOT NULL"
                ).bind("fractions", StyleClassifier.fractions(classes).toTypedArray())
                .bind("organizationId", user.organizationId)
                .map { row, _ -> row.get("breaks", Array<Double?>::class.java) ?: emptyArray() }
                .one()
                .awaitFirstOrNull()
        return breaks?.filterNotNull().orEmpty()
    }
}
