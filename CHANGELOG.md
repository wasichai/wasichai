# Changelog

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
