# ADR-075: A read scope narrows by another object through a subquery core renders

**Status**: accepted · 2026-11-01 · extends [ADR-048](0048-a-read-scope-narrows-what-a-caller-reads.md)

## Context

A `RecordReadScope` (ADR-048) returns a `RecordCriterion`: plain SQL over the object being read. When the scope
depends on other objects (a lesson is read when its unit belongs to a version the caller is enrolled in), an app had
two ways, both bad ([wasichai#147](https://github.com/wasichai/wasichai/issues/147)):

1. Read the related objects as the caller through `RecordService` and bind their ids (`col = ANY(?)`). Core applies
   every rule, but the bound array grows with data the screen does not show, and each level costs a statement.
2. Write the subquery by hand over the physical tables. One statement, but the app re-implements core's read rules
   (`organization_id`, "no `READ`, nothing", `own_records_only`, the other object's scopes) and depends on how core lays
   out its data tables.

The second user is wasichai/elearning-backend, which moved from 1 to 2 after measuring the growing arrays
([elearning-backend#236](https://github.com/wasichai/elearning-backend/pull/236)) and repeats every rule but
`own_records_only` by hand.

## Decision

### `RecordSubqueries`, a core bean

```kotlin
class RecordSubqueries {
    suspend fun select(caller: AuthenticatedUser, objectName: String, select: String = "id", where: Map<String, Any?> = emptyMap()): RecordSubquery
}

fun interface RecordSubquery {
    fun sql(bind: (Any) -> String): String
    fun criterion(field: String): RecordCriterion // "<field> IN (<subquery>)"
}

fun ObjectDefinition.column(name: String): String // the quoted column of a field, or id
```

A scope takes the bean and prepares its subqueries inside `criterion`, which is `suspend`; the `RecordCriterion` it
returns stays the non-suspending shape and only renders SQL:

```kotlin
val units = subqueries.select(caller, "unidad", where = mapOf("version_curso" to subqueries.select(caller, "version_curso")))
return units.criterion("unidad")
```

`select` is `id` or a field name. A `where` entry names a field (or `id`) and holds a value, `null` (`IS NULL`) or
another `RecordSubquery` (`IN`). A value goes through its field type's `toDatabase`, as a list filter's does, and is
bound. Names come from metadata: no table or column is written by the app. An unknown object is the
`NotFoundException` of `MetadataService.loadDefinition`; an unknown field an `IllegalArgumentException`.

### What it applies to the other object

What `RecordService.list` applies when the same caller reads it:

- `organization_id` of the caller, always;
- `READ` on it: none, and the subquery is `WHERE false` (fail closed), with its scopes never asked;
- `own_records_only`: `created_by` is the caller;
- its own read scopes, each in parentheses, with the object's full definition.

`ADMIN` holds `READ` everywhere, has no owner filter and no scope, so it gets the organization, as everywhere. Not
applied, as for any `RecordCriterion`: field rules (the subquery may name a field the caller cannot read; only which
values match reaches the outer read) and read masks (nothing of the rows leaves the statement).

The rules are resolved in Kotlin when the subquery is prepared, from the same request-memoised reads `RecordService`
uses (`CurrentUser.hasPermission`, `AccessPolicy.ownerFilter`, ADR-066). Nothing of the role tables is folded into the
SQL, unlike D30's `RelationTargets`, because a scope is asked once per read anyway.

### Cycles

Scopes that reach an object again through subqueries (lesson's scope reads units, units' scope reads lessons) would
recurse forever. The objects whose scopes are being asked ride the coroutine context; meeting one again throws
`IllegalStateException` naming the chain, at the read, not at boot.

### Wiring

`RecordSubqueries` is `@ConditionalOnMissingBean` in `WasichaiDataAutoConfiguration`. It reaches `RecordReadScopes`
lazily: that bean takes every scope, and a scope takes this one.

## Consequences

- An app keeps its scope in one statement, without core's table layout and without repeating its read rules,
  `own_records_only` included. Nothing changes for an app that does not use it: no ADR-031 entry, no REST change.
- An inner subquery's unqualified columns resolve to its own table first, so the outer object's columns are never
  reached by accident; a scope written for the other object keeps working unchanged inside the subquery.
- Cost: one definition lookup (cached, ADR-067), one memoised access read, and the other object's scopes per
  `select`. A deep chain is one statement with nested `IN`s; its plan is the database's.
- Tested by `RecordSubqueriesTest`, `WasichaiAutoConfigurationTest` and the integration test `RecordSubqueryApiTest`.
