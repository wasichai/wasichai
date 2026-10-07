# Admin audit trail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every change to users, roles, permissions, service accounts, organizational units, the model and the
tenant in `audit_log` ([#49](https://github.com/wasichai/wasichai/issues/49)), and let a `MANAGE_ORGANIZATION` holder
read them at `GET /api/audit`, with no change to the record trail.

**Architecture:** A port in `identity`, `AdminAudit`, with `AdminEntity` (the reserved `admin:*` object names) and
`AdminOperation`. `identity` sits below `metadata`, `admin` and `organization` in core's DAG, so all three can call it;
`audit` implements it over `AuditService` (`AuditLogAdminAudit`). Each admin service writes one row at the end of its
`@Transactional` method, so a refused call writes nothing and a rollback takes the row with it. Snapshots are plain
maps built in each package, never from a row that holds a hash. Permission sets are flattened to one key per grant, so
`AuditDiff` shows only the grants that changed. `AuditQueryService` answers `admin:*` to `MANAGE_ORGANIZATION` only:
`[]` for anyone else, hidden from an unfiltered list in SQL, never narrowed by a read scope or field permissions.
Decision: ADR-049.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, R2DBC `DatabaseClient`, JUnit 5 + AssertJ + Mockito.

## Global Constraints

- No migration: `audit_log` has no foreign key on `organization_id`, `object_name` or `record_id`, and every admin
  entity has a uuid id. `audit_log_operation_valid` untouched.
- Record entries, their diff and their read rules unchanged.
- No secret in a row: no password hash, no secret hash, no client secret.

## Task 1: The port and the writer

- [ ] `identity/AdminAudit.kt`: `AdminAudit`, `AdminEntity`, `AdminOperation`.
- [ ] `audit/AuditLogAdminAudit.kt`; bean `adminAudit` in `WasichaiDataAutoConfiguration`.
- [ ] `CurrentUser.hasPermission` (no throw), `requirePermission` on top of it.

## Task 2: The writers

- [ ] `AdminService`: users (create, update with `passwordChanged`, roles, delete), roles (create, update, delete),
  permissions and field permissions (flattened sets).
- [ ] `ServiceAccountService`: create, update, rotate (`secretRotated`), delete.
- [ ] `OrgUnitService`: units (`admin:org-unit`), user units (`admin:user`).
- [ ] `OrganizationService`: provision (in the provisioner's tenant), rename, delete.
- [ ] `MetadataService`: objects (with fields and declared actions), fields; a relationship's own field is not a field
  row. `ObjectActionService`: declare and remove as `admin:object` updates. `RelationshipService`: create, update,
  delete.

## Task 3: The read

- [ ] `AuditQueryService.list`: `admin:*` filter → `MANAGE_ORGANIZATION` or `[]`; unfiltered → admin rows only for
  its holders, in SQL; read scope and field permissions skip admin rows; their `CREATE`/`DELETE` diff every key.

## Task 4: Tests and docs

- [ ] Unit: `AdminSnapshotsTest`, `AuditQueryServiceAdminTest`, `AdminAuditWiringTest` (in the autoconfig test).
- [ ] Integration: `AdminAuditApiTest` (each endpoint, read rules, no secrets, refused and rolled-back calls).
- [ ] ADR-049, ADR-031 D34, HISTORY, rest.md, core.md.
