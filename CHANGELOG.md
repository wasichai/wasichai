# Changelog

## [0.5.0](https://github.com/wasichai/wasichai/compare/v0.4.0...v0.5.0) (2026-10-08)


### Notes

* **migrations, run on upgrade:** core `V10__audit_origin` (`audit_log.correlation_id`, `source`), `V12__audit_user_index`, `V13__audit_log_immutable` (triggers refuse `UPDATE`, `DELETE` and `TRUNCATE` on `audit_log` for every role, ADR-054), `V15__manage_tenants` and the repeatable `R__audit_purge_role`; automation `V2__run_correlation_id`
* **core:** new properties `wasichai.audit.purge-role` (the only login that may purge the audit log, none by default) and `wasichai.organizations.separate-provisioning` (default `false`: creating and deleting tenants stays with whoever passes `MANAGE_ORGANIZATION`, through the new built-in action `MANAGE_TENANTS`, ADR-055); `spring.reactor.context-propagation` defaults to `auto`
* **core:** behaviour an upgrade sees: a create fills each attribute it leaves out with the field's `defaultValue` (ADR-031 D40); records answer an `ETag` and take `If-Match` and `PATCH` (ADR-051), and `updated_at` moves with `clock_timestamp()`; every response carries `X-Correlation-Id` (ADR-050); `GET /api/auth/me/permissions` adds `capabilities`; a self-relationship is listed twice, forward then inverse (D42)
* **core:** API for apps and modules: the `RecordReadScope` SPI (ADR-048) and `TenantDirectory` (ADR-057); `RecordService`, `RelatedRecordService`, `AuditQueryService`, `WorkflowService` and the admin services take new constructor arguments; `RecordStore.findById` takes `criteria` and the port gains `updateIfUnchanged`, `deleteIfUnchanged` and `transitionStateIfUnchanged` with refusing defaults; `OrganizationRepository.ids()` is gone and `CustomObjectRepository.findAllOrganizations()` is deprecated
* **build:** security floors over the Spring Boot BOM (Jackson 2 and 3, SCRAM, logback, the Kotlin plugin), applied as `api` so apps on the modules get them


### Features

* **automation:** a run keeps the request's correlation id and labels its writes ([0345303](https://github.com/wasichai/wasichai/commit/0345303718d57cf77cb7ae5566156edc96a03bfb)), closes [#50](https://github.com/wasichai/wasichai/issues/50)
* **core:** a RecordReadScope SPI narrows what a caller reads of an object ([57abaf3](https://github.com/wasichai/wasichai/commit/57abaf3664c657d96e4f13a4a97a5f088b240852)), closes [#48](https://github.com/wasichai/wasichai/issues/48)
* **core:** a RecordReadScope SPI so an app can limit what a caller reads ([0cf194e](https://github.com/wasichai/wasichai/commit/0cf194efa79ad8148750b1a434bd411e07079d9a))
* **core:** a TenantDirectory for background work that runs per tenant ([ae3341c](https://github.com/wasichai/wasichai/commit/ae3341ce88cd00f70753e053a76209a9b57faa9a))
* **core:** a TenantDirectory for background work that runs per tenant ([b327f28](https://github.com/wasichai/wasichai/commit/b327f2830ad5bd61b789d3ab7a0cc99121b88305))
* **core:** make audit_log append-only in the database, with a purge only a configured login runs ([c644754](https://github.com/wasichai/wasichai/commit/c644754ab5e66704609bb50025d6c466ac18a743))
* **core:** make audit_log append-only in the database, with a purge only a configured login runs ([2a20efc](https://github.com/wasichai/wasichai/commit/2a20efc5b1b5c13d6b810c7d791013f2f4be4c95)), closes [#58](https://github.com/wasichai/wasichai/issues/58)
* **core:** page and filter the audit log by date range and user ([e80dd76](https://github.com/wasichai/wasichai/commit/e80dd760c3ce84a81f3f39c53abb567ac1e8d384))
* **core:** page and filter the audit log by date range and user ([a08aa1a](https://github.com/wasichai/wasichai/commit/a08aa1a361ecdb5b4d3fb0888d1d62c7e1b8d6cb)), closes [#52](https://github.com/wasichai/wasichai/issues/52)
* **core:** read a self-relationship from either end with direction=forward|inverse ([6e59001](https://github.com/wasichai/wasichai/commit/6e5900124500dc6e2272c0f7b934654dfb7bf48f))
* **core:** read a self-relationship from either end with direction=forward|inverse ([65363bb](https://github.com/wasichai/wasichai/commit/65363bbe85bfcd16ddd604f2c941d01752477a5f)), closes [#61](https://github.com/wasichai/wasichai/issues/61)
* **core:** record changes to users, roles, permissions and metadata in the audit log ([1fc3da7](https://github.com/wasichai/wasichai/commit/1fc3da71b228b88cb6d9150c7401420e19f1b8a9))
* **core:** record changes to users, roles, permissions and metadata in the audit log ([88db868](https://github.com/wasichai/wasichai/commit/88db868a14f4a51728e8d4346f032acc30102e64)), closes [#49](https://github.com/wasichai/wasichai/issues/49)
* **core:** records answer an ETag, compare If-Match in the write itself, and take a partial PATCH ([0e44abc](https://github.com/wasichai/wasichai/commit/0e44abc8b4fc84b27e8700f6d7626c00eb3b2b6e))
* **core:** records answer an ETag, honour If-Match in the write itself, and take a partial PATCH ([12f3abc](https://github.com/wasichai/wasichai/commit/12f3abc4005f4fc1b13ae35bf6410dbea9e697cb))
* **core:** report tenant-wide capabilities in GET /api/auth/me/permissions ([b5f49a8](https://github.com/wasichai/wasichai/commit/b5f49a826451c8dbbc85fcc5896fddaf7075b8ba))
* **core:** report tenant-wide capabilities in GET /api/auth/me/permissions ([eecb2fc](https://github.com/wasichai/wasichai/commit/eecb2fc948b2a569958a43b609c16ded73500484)), closes [#53](https://github.com/wasichai/wasichai/issues/53)
* **core:** separate tenant provisioning and deletion behind MANAGE_TENANTS ([cab4a18](https://github.com/wasichai/wasichai/commit/cab4a18baf1fe983211649aa6b9d3b5df8523d61)), closes [#56](https://github.com/wasichai/wasichai/issues/56)
* **core:** separate tenant provisioning and deletion from MANAGE_ORGANIZATION ([4e06d62](https://github.com/wasichai/wasichai/commit/4e06d627778124f875aba975055113dc3b528bf1))
* **core:** store a correlation id and the source of the change on every audit row ([ce6ff8e](https://github.com/wasichai/wasichai/commit/ce6ff8e48aea87ce93dfc171e4b2faea627a77e9))
* **core:** store a correlation id and the source of the change on every audit row ([3d1aa64](https://github.com/wasichai/wasichai/commit/3d1aa64652e69a7c006d3ef3c42d1c5fc94cfb30)), closes [#50](https://github.com/wasichai/wasichai/issues/50)
* **workflow:** transitions look the record up in the caller's read scope ([cb59ad6](https://github.com/wasichai/wasichai/commit/cb59ad661b150d60111066653761ff39fcc0b905)), closes [#48](https://github.com/wasichai/wasichai/issues/48)


### Bug Fixes

* **core:** apply a field's defaultValue when a record is created without it ([e0d61a7](https://github.com/wasichai/wasichai/commit/e0d61a72dabaeebcff914ef1c932a2a0afe2de3e))
* **core:** apply a field's defaultValue when a record is created without it ([4c441cd](https://github.com/wasichai/wasichai/commit/4c441cdb8a3503e005de7520f609c5e7f89b0cde)), closes [#60](https://github.com/wasichai/wasichai/issues/60)
* **core:** keep the default read of a ONE_TO_MANY self-relationship as it was ([6d7bebf](https://github.com/wasichai/wasichai/commit/6d7bebf5f5d8ac178e715c3cecab1ca41463fb54)), closes [#61](https://github.com/wasichai/wasichai/issues/61)
* **deps:** lift vulnerable jackson, scram, logback and kotlin plugin versions ([3f9eac6](https://github.com/wasichai/wasichai/commit/3f9eac6aa57bec8be5047ccb0c00f3e3bc7916bb))
* **deps:** lift vulnerable jackson, scram, logback and kotlin plugin versions ([e2d4bcb](https://github.com/wasichai/wasichai/commit/e2d4bcbdce9c7b2026c4c2ca1a860905fdc515af))

## [0.4.0](https://github.com/wasichai/wasichai/compare/v0.3.3...v0.4.0) (2026-10-07)


### Notes

* **core:** organizational units ([ADR-045](docs/adr/0045-organizational-units.md)): migration `V9__org_units` adds `org_units` and `user_org_units`, and runs on upgrade; routes `/api/org-units`, `PUT /api/users/{id}/org-units` and `GET /api/auth/me/org-units`; `AdminUserResponse` gains `orgUnits`. Units grant nothing and are not in the token
* **core:** new ports for modules: `OrgUnitDirectory`, `UserDirectory`, `OrganizationRepository.ids()` and the public `Connections.unpooled`
* **pages:** a `TAB` may carry a `key`, an upper-case token unique in its page; generated pages key their tabs `DETAILS`, `RELATED`, `HISTORY` and `MAP`. A component without a key is stored and sent as before
* **notifications:** new opt-in module `wasichai-notifications` and starter `wasichai-spring-boot-starter-notifications` ([ADR-046](docs/adr/0046-notifications-module.md), [ADR-047](docs/adr/0047-server-push-over-sse-and-listen-notify.md)): notifications for everyone, a user, a role or a unit, published from code (`Notifications`), scheduled sources (`NotificationSource`) and date rules, an inbox at `/api/auth/me/notifications` and a live summary over SSE fed by PostgreSQL `LISTEN/NOTIFY`. Without the module every route answers `404`
* the release guard now expects 22 artifacts


### Features

* **notifications:** alerts for people, roles and organizational units ([#46](https://github.com/wasichai/wasichai/pull/46), [#66](https://github.com/wasichai/wasichai/pull/66))

## [0.3.3](https://github.com/wasichai/wasichai/compare/v0.3.2...v0.3.3) (2026-10-07)


### Notes

* **core:** refactors with no change in behaviour: `RecordService` opens and describes each write in one place, user and service-account administration share one role assignment ([#47](https://github.com/wasichai/wasichai/pull/47)).
* **core:** new public `wasichai.core.platform.bindNullable(name, value)` and `bindNullable(name, value, type)` for nullable R2DBC binds; the `internal` copy in `metadata` is gone ([#47](https://github.com/wasichai/wasichai/pull/47)).
* **core:** a service account's role inserts skip a role already held, as a user's always did; only two concurrent saves of one account can tell ([#47](https://github.com/wasichai/wasichai/pull/47)).


### Performance Improvements

* **core:** a role's permission payload reads each object once ([019a911](https://github.com/wasichai/wasichai/commit/019a911a387965b537b7942eaa001a88da58f7cb))

## [0.3.2](https://github.com/wasichai/wasichai/compare/v0.3.1...v0.3.2) (2026-10-03)


### Notes

* **core:** a RELATION value set by a person or service account (not ADMIN) must name a record they can read (READ on the target object, and their own when their roles are own-records-only); otherwise it answers the same `400` as a missing record (ADR-031 D30).
* **core:** `RecordWriteGuards.beforeWrite` and `RelationTargets.rejectMissing` take a `reader` (source compatible); a user's write that sets RELATION values without it fails ([#41](https://github.com/wasichai/wasichai/pull/41)).


### Bug Fixes

* **core:** a RELATION value names only a record the caller can read ([f8910f4](https://github.com/wasichai/wasichai/commit/f8910f4f16b8992546ba4b1e0016a5ece1c93714))
* **core:** a RELATION value names only a record the caller can read ([bc13b74](https://github.com/wasichai/wasichai/commit/bc13b743d95e102ac93cbed57eda14602b35fe5a)), closes [#39](https://github.com/wasichai/wasichai/issues/39)
* **core:** fail closed when a user's RELATION write passes no reader ([d0547ed](https://github.com/wasichai/wasichai/commit/d0547ede854f81b12568587be7381fd4fad40845)), closes [#39](https://github.com/wasichai/wasichai/issues/39)
* **test:** bind the integration test server to 127.0.0.1 ([5ab0024](https://github.com/wasichai/wasichai/commit/5ab0024399ee413a64d455a8f6c15e38f98e33bc))
* **test:** bind the integration test server to 127.0.0.1 ([d8f3afe](https://github.com/wasichai/wasichai/commit/d8f3afe8e73c81762881db54037df60213103f37)), closes [#34](https://github.com/wasichai/wasichai/issues/34)
* **test:** explain a tokenless login without printing the password ([101f1dc](https://github.com/wasichai/wasichai/commit/101f1dcf5f9bbcd2eb7fae0d85a1a22d5b249885)), closes [#34](https://github.com/wasichai/wasichai/issues/34)

## [0.3.1](https://github.com/wasichai/wasichai/compare/v0.3.0...v0.3.1) (2026-10-03)


### Notes

* **core:** a RELATION value naming no record, or one of another organization, now answers `400` with `errors[].field` (ADR-031 D29; it was `409` in 0.3.0).
* **core:** `RecordWriteGuards` takes `RelationTargets` as a second constructor parameter; only code that builds it by hand (tests) changes ([#38](https://github.com/wasichai/wasichai/pull/38)).


### Bug Fixes

* **core:** answer 400 for a RELATION value naming no record ([ede5e52](https://github.com/wasichai/wasichai/commit/ede5e52b7d59c6970cabf3d93cf1c170185f5176))
* **core:** answer 400 for a RELATION value naming no record ([7d25d58](https://github.com/wasichai/wasichai/commit/7d25d58f4d594a81ffa9b51b8df9c84eb1c1ffea)), closes [#33](https://github.com/wasichai/wasichai/issues/33)
* **core:** check RELATION targets in RecordWriteGuards, after the write rules ([b996a58](https://github.com/wasichai/wasichai/commit/b996a58cf19ec2393f31c87b1476706eba731d6e)), closes [#33](https://github.com/wasichai/wasichai/issues/33)

## [0.3.0](https://github.com/wasichai/wasichai/compare/v0.2.0...v0.3.0) (2026-10-03)


### ⚠ BREAKING CHANGES

* **core:** `RecordStore.insert`/`update` take `userId: UUID?`; an app's own `RecordStore` must change and be recompiled ([#26](https://github.com/wasichai/wasichai/pull/26)).
* **core:** `PageResponse.totalElements`/`totalPages` are nullable, and `CustomObjectRepository` takes a `JsonMapper` ([#23](https://github.com/wasichai/wasichai/pull/23)).
* **core:** `RecordService`, `RelatedRecordService`, `WorkflowService` and `AutomationRunner` take `RecordWriteGuards`; the REST controllers no longer reach an app subclass's overrides of the public write methods; a no-op link or unlink on an append-only end answers 409 instead of 204 ([#27](https://github.com/wasichai/wasichai/pull/27)).
* **core:** `AuditService.record` takes a `reason`, and `RecordWrite` gains a trailing property (recompile) ([#28](https://github.com/wasichai/wasichai/pull/28)).
* **core:** `AppendOnlyReferences` takes a `transactions` parameter and `rejectDelete` became `deleting { }` ([#32](https://github.com/wasichai/wasichai/pull/32)).
* **core:** observable answers that change (ADR-031): ties in a list now order by `id` (D21); `count` and `after` are reserved parameters and field names (D22); a unique violation answers 409 with `errors[].field` (D23); a foreign-key violation answers 409 (D28).


### Features

* **automation:** run rules triggered by a platform write ([f1eb5b8](https://github.com/wasichai/wasichai/commit/f1eb5b82d8f346255679e73ddc5f2f4831d2a0af))
* **core:** an optional change reason on record writes, required per object ([e42b56a](https://github.com/wasichai/wasichai/commit/e42b56a193573808b18f785f778cc444395e658d)), closes [#19](https://github.com/wasichai/wasichai/issues/19)
* **core:** app-declared actions beyond CRUD ([da0c077](https://github.com/wasichai/wasichai/commit/da0c077fb88c85fe13b4852b810a8a03002f27a7))
* **core:** app-declared actions beyond CRUD ([5e8322e](https://github.com/wasichai/wasichai/commit/5e8322ebefa4be342d8c395131238dd2f269fc0f)), closes [#16](https://github.com/wasichai/wasichai/issues/16)
* **core:** append-only and api-only objects, and a pre-write guard SPI ([1dd692a](https://github.com/wasichai/wasichai/commit/1dd692a788dcbfeb88769860ed1bd86ca797da99)), closes [#15](https://github.com/wasichai/wasichai/issues/15)
* **core:** ClusterLock over postgres advisory locks ([7658e60](https://github.com/wasichai/wasichai/commit/7658e60ba317deaecd0cab2a85e0f930f42f31b0))
* **core:** composite unique constraints, and unique violations as 409 ([4946c24](https://github.com/wasichai/wasichai/commit/4946c2464cd8237f29d1d4ce75c0f8101d0811df))
* **core:** composite unique constraints, and unique violations as 409 ([a7e526b](https://github.com/wasichai/wasichai/commit/a7e526bc47f7056048cd96bc60645bd8fe20801d)), closes [#14](https://github.com/wasichai/wasichai/issues/14)
* **core:** declared indexes, optional list count and keyset reads ([fec1404](https://github.com/wasichai/wasichai/commit/fec14042df8ded68545f6267bf363c15ff7c4f4d)), closes [#21](https://github.com/wasichai/wasichai/issues/21)
* **core:** run RecordService as the platform for background work ([2a57519](https://github.com/wasichai/wasichai/commit/2a5751955c161cb4fd1c9e82d2c5e3c6a0a4238b))
* **core:** service accounts for server-to-server callers ([e9edc96](https://github.com/wasichai/wasichai/commit/e9edc968c0b60509d95157abcd50b087c4c1cb73))
* **core:** service accounts for server-to-server callers ([183ef2f](https://github.com/wasichai/wasichai/commit/183ef2fe78aed77c614c1138b6fcfa53c16057da)), closes [#17](https://github.com/wasichai/wasichai/issues/17)


### Bug Fixes

* **core:** check append-only references under a row lock, and answer a lost reference with 409 ([fd7fc32](https://github.com/wasichai/wasichai/commit/fd7fc32d7b4249af940f1af0ba76c3013e3e1c90))
* **core:** check append-only references under a row lock, in one transaction with the delete ([967ada1](https://github.com/wasichai/wasichai/commit/967ada181b4713e84721ef15b4fdb4d655a080ce))
* **core:** end every record ORDER BY with id as a unique tie-breaker ([e5985d6](https://github.com/wasichai/wasichai/commit/e5985d6278c06073e22c98d9af35e8b07d97054c))
* **core:** end every record ORDER BY with id as a unique tie-breaker ([016b25b](https://github.com/wasichai/wasichai/commit/016b25bb9845c9c3e12dd42a26eeb92ba27a794e)), closes [#20](https://github.com/wasichai/wasichai/issues/20)
* **core:** field types cannot claim the appendOnly and apiOnly object keys ([f173fcb](https://github.com/wasichai/wasichai/commit/f173fcbc6327816c68848d3b462701cb493c1f39))
* **core:** field types cannot claim the requiresReason object key, and a test keeps the set whole ([61a3a8b](https://github.com/wasichai/wasichai/commit/61a3a8bff0a8aab63b6d075baa95c75e46ba3696))
* **core:** field types cannot claim the uniqueConstraints object key ([b3eb55c](https://github.com/wasichai/wasichai/commit/b3eb55c6f73bccb2f5abcc41320cb24817bf89a3))
* **core:** guard indexed relation columns, 400 for bad cursor values, record ADR-031 D22 ([65af212](https://github.com/wasichai/wasichai/commit/65af212a1cd22ca3bf7c1e0152e48aa1698bb765)), closes [#21](https://github.com/wasichai/wasichai/issues/21)
* **core:** hash checks off the event loop, and a person's answers unchanged ([790a5f3](https://github.com/wasichai/wasichai/commit/790a5f36af6e1b92cfaced158d19739583d04d35))
* **core:** keep a refused delete the caller's, and answer a lost reference with 409 ([00e8760](https://github.com/wasichai/wasichai/commit/00e8760deb95e753bcef2c4e0994fca9763f7906))
* **core:** keep an append-only object's links when the other end is deleted ([39da264](https://github.com/wasichai/wasichai/commit/39da26414376132ccdfe32cf371f85f09d12e17f))
* **core:** keep composite uniques when a field's unique toggles, drop the organization prefix ([8ca5cd0](https://github.com/wasichai/wasichai/commit/8ca5cd03a7f1b471aaf5f2805d71e3c4655699c6)), closes [#14](https://github.com/wasichai/wasichai/issues/14)
* **core:** refuse control characters in a change reason, strict percent escapes ([f761b86](https://github.com/wasichai/wasichai/commit/f761b8637f87b7e5032045cb8127c11adc350241))
* **core:** refuse deleting a record an append-only record points at ([750bcad](https://github.com/wasichai/wasichai/commit/750bcad33d75dcfe067342c0fcf63ce4c51c3613))
* **core:** refuse one-field unique constraint entries ([213ebeb](https://github.com/wasichai/wasichai/commit/213ebeb6fd9c90db8e592b976473a39d17d8b16e)), closes [#14](https://github.com/wasichai/wasichai/issues/14)
* **core:** scope every object action statement to the tenant ([0a2cfc8](https://github.com/wasichai/wasichai/commit/0a2cfc87f1ca9f6c4095354e4f60bd2c427a628b))
* **core:** unlock a ClusterLock lease before closing its connection ([82a05c7](https://github.com/wasichai/wasichai/commit/82a05c74f4e980142fdec936d75b4f152c67a99a))

## [0.2.0](https://github.com/wasichai/wasichai/compare/v0.1.0...v0.2.0) (2026-09-26)


### Features

* **core:** store the caller's theme and locale preferences ([#3](https://github.com/wasichai/wasichai/issues/3)) ([31869ab](https://github.com/wasichai/wasichai/commit/31869abcaeced12823350c7aea0b72888b731219))

## [0.1.0](https://github.com/wasichai/wasichai/compare/v0.1.0...v0.1.0) (2026-09-26)


### Features

* initial project setup with initial classes ([1e1ee41](https://github.com/wasichai/wasichai/commit/1e1ee41a6a1625c2df6a18e871b89d4d239d2a8a))
