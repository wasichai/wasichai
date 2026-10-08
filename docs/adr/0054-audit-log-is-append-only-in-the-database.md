# ADR-054: The audit log is append-only in the database, with a purge only a configured login can run

**Status**: accepted · 2026-10-08 · builds on [ADR-008](0008-flyway-over-jdbc.md),
[ADR-026](0026-per-module-migrations.md), [ADR-040](0040-append-only-objects-and-a-pre-write-guard.md),
[ADR-049](0049-admin-changes-in-the-audit-log.md) and [ADR-050](0050-correlation-id-and-change-source-on-audit-rows.md)

## Context

`audit_log` was append-only by convention only: `AuditService` inserts and nothing in wasichai updates or deletes a
row, but the table itself was an ordinary one. The credential wasichai connects with is, by default, the one Flyway
migrates with (ADR-008), so it owns the table and can `UPDATE`, `DELETE` and `TRUNCATE` it. So could a bug in app code
using `DatabaseClient` directly, or an operator in `psql`, without a trace. `appendOnly` objects (ADR-040) protect
records from every caller of the platform; their audit trail was not protected from the database.

A social-management app (SGSPE) promises that its trail cannot be altered, its own application credential included,
because the trail is the evidence in disputes and audits ([#58](https://github.com/wasichai/wasichai/issues/58)).

Two things must keep working: wasichai-documents' `audit_log_document_id_fkey` is `ON DELETE SET NULL`, so deleting a
document (directly or through its tenant) updates the audit rows that point at it; and an operator bound by a retention
policy must have a way to purge.

## Decision

### Triggers refuse every change, for every role

Core's `V13__audit_log_immutable.sql` creates `audit_log_guard()` and two triggers on `audit_log`:
`audit_log_append_only`, `BEFORE UPDATE OR DELETE … FOR EACH ROW`, and `audit_log_no_truncate`,
`BEFORE TRUNCATE … FOR EACH STATEMENT`. The function raises
`audit_log is append-only: <UPDATE|DELETE|TRUNCATE> is not allowed`, SQLSTATE `42501` (`insufficient_privilege`),
with a hint naming this ADR. Triggers fire for every role, the owner and a superuser included; only an owner (or a
superuser) can drop or disable them, which is what the two-role setup below is for. `INSERT` and `SELECT` are
untouched. The migration is idempotent (`CREATE OR REPLACE FUNCTION`, `DROP TRIGGER IF EXISTS`), and the function pins
`search_path = pg_catalog, pg_temp` and calls its one helper schema-qualified, so no object a role creates elsewhere can
stand in for a built-in.

Triggers, not `REVOKE`: privileges do not bind the owner, and wasichai's default is one role that owns everything. A
`REVOKE` is still what the guide recommends for the runtime role, as a second layer.

### Two holes, each as narrow as the case

- **The foreign-key `SET NULL`.** An `UPDATE` passes when `document_id` goes from a value to `NULL`, every other column
  is unchanged (`(to_jsonb(OLD) - 'document_id') = (to_jsonb(NEW) - 'document_id')`) and it runs inside another
  trigger (`pg_trigger_depth() > 1`): the referential action, fired by the `DELETE` of a document or by the cascade of
  a tenant's deletion. The same `UPDATE` written as a statement of its own is refused. Core does not know the
  documents table, so it checks the shape of the change, not the constraint that made it; a role that can create a
  trigger of its own could null a `document_id` from it, and nothing more.
- **Purge.** `DELETE` and `TRUNCATE` (never `UPDATE`) pass when the transaction ran
  `SET LOCAL wasichai.audit.purge = 'on'` **and** `session_user` is the role `wasichai.audit.purge-role` names.
  - `session_user`, not `current_user`: `SET ROLE` and a `SECURITY DEFINER` function change `current_user`, a login does
    not, and only a superuser can change `session_user` (`SET SESSION AUTHORIZATION`). So a runtime role that happens
    to be a member of the purge role still cannot purge: it has to log in as it. Equality, not membership, for the same
    reason.
  - The role's name lives in the database, in `audit_log_purge_role()`, which core's repeatable migration
    `R__audit_purge_role.sql` writes from the Flyway placeholder `auditPurgeRole`, that is, from
    `wasichai.audit.purge-role` (`WasichaiAuditProperties`). Flyway checksums a repeatable migration with its
    placeholders replaced, so a new value runs it again on the next migration. Default: none, the function returns
    `NULL`, nobody can purge.
  - We weighed a database setting (`ALTER DATABASE … SET`) and a config table the app writes at startup. Any session can
    `SET` a custom setting, and a table the runtime role writes is a table the runtime role can point at itself. The
    function is owned by the migration role, so in the two-role setup the runtime role can neither replace it nor
    create a substitute in its schema. The property is validated as a plain unquoted role name
    (`^[a-z_][a-z0-9_]{0,62}$`) at binding: it is written into the function body.
  - The flag is a session setting on purpose: an operator states the intent in the transaction that purges, and it
    ends with it. The purge role still needs `DELETE` (and `TRUNCATE`) on the table: the trigger opens nothing that
    privileges do not.

### Two database roles, recommended, and a check at startup

The guide recommends a migration role that owns the schemas and runs Flyway, and a runtime role that connects with
`SELECT` and `INSERT` on `audit_log` and the usual rights elsewhere. An app gets there by replacing the
`wasichaiMigrations` bean with one built from a copy of `WasichaiDatabaseProperties` holding the owner's credentials,
or by migrating in a deploy step of its own with `wasichai.database.migrate=false` on the service. No new database
property: the bean override already is the seam. `WasichaiMigrations` gains the audit properties as a fourth
constructor argument; the three-argument constructor stays, meaning no purge role, so code compiled against it keeps
linking.

`AuditLogOwnershipCheck` (bean `auditLogOwnershipCheck`, `@ConditionalOnMissingBean`, in
`WasichaiDataAutoConfiguration`) runs once the app is ready. When the role wasichai runs as can act as the owner of
`audit_log` (`pg_has_role(current_user, owner, 'MEMBER')`: the owner, a member of it, or a superuser), it logs one
`WARN` naming both roles and the fix. It never fails startup: an error or a 10-second timeout is an `INFO` line.

### What stays as it was

- Tenant deletion (`DELETE /api/organizations/current`) never touched `audit_log`: there is no foreign key to
  `organizations`. The deleted tenant's entries stay, as they did, and their documents' `ISSUE` rows lose only
  `document_id`.
- No migration of wasichai, core or module, updates or deletes `audit_log` rows; adding a column or a constraint is DDL,
  which no trigger sees. A future migration that must rewrite rows runs as the owner and disables the trigger around
  its statement, in the same transaction.
- The test suites wipe an external database with `DROP TABLE … CASCADE` and `DROP SCHEMA … CASCADE`, never a `DELETE`
  or `TRUNCATE`, and every test reads only its own rows: nothing in the test support needed to change.

## Consequences

- The trail of a record can no longer be rewritten by the platform's own credential in the two-role setup, nor by
  anyone through SQL short of dropping the trigger as the owner. In the one-role default it stops mistakes and
  `DatabaseClient` code; the startup `WARN` says what remains open.
- Retention is possible without weakening that: the operator names a purge role in the migration's configuration,
  grants it `DELETE`, and purges in a transaction that sets the flag.
- The schema gains two functions and two triggers on `audit_log`: ADR-031 D39, a known schema-parity deviation. The
  catalog compares function bodies by `md5`, so a change to V13's function or to the repeatable migration changes the
  expected line in `SchemaParityTest`.
- An app that runs migrations another way (`wasichai.database.migrate=false`) applies the same scripts with the same
  placeholders, `auditPurgeRole` included (empty for none).
- Tested by `CoreMigrationSqlTest`, `WasichaiMigrationsTest`, `WasichaiAutoConfigurationTest`,
  `AuditLogOwnershipCheckTest`, and the integration tests `AuditLogAppendOnlyApiTest` (the three statements, the
  set-null path, the purge path, a non-owner runtime role, the startup check, the repeatable migration),
  `AdminAuditApiTest` (a deleted tenant's entries stay) and `DocumentsOnlyApiTest` (a tenant with issued documents is
  deleted and its trail stays, the `ISSUE` rows losing only `document_id`).
