# Declared roles and realm service accounts, backend: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** an app declares its roles in code next to its model, ensured in every tenant and locked like the model; and,
in `oidc` mode, a confidential client of the domain's realm that the app's configuration lists is one of its service
accounts, in the token's organization, with the configured roles and every limit of ADR-043.

**Architecture:** `DeclaredModel.roles` is ensured by `DeclaredModels` after objects, relationships and actions, inside
the same `ModelOwner.run`, through `AdminService`'s own role methods, reached over a port (`DeclaredModelRoles`) that
`metadata` owns and `admin` implements. `roles.declared_by` locks them. For the realm, `RealmPrincipalConverter` gains a
service-account branch: a token with no `email` and `preferred_username = service-account-<azp>` resolves, through
`RealmServiceAccounts`, to the `service_accounts` row with `(organization_id, external_client_id = azp)`, created on
the first token and kept in step with `wasichai.security.oidc.service-accounts`; the translated token carries
`service_account`, so `CurrentUser` and every permission check stay as they are.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, Spring Security resource server (Nimbus), R2DBC `DatabaseClient`,
Flyway, JUnit 5, AssertJ, Testcontainers.

**Spec:**
[the design](../specs/2026-11-16-declared-roles-and-realm-service-accounts-design.md),
[ADR-085](../../adr/0085-roles-an-app-declares-in-code.md),
[ADR-086](../../adr/0086-service-accounts-signed-by-the-domains-identity-provider.md).

## Global Constraints

- A model with no `roles` behaves exactly as today; with `identity=local`, or `oidc` and no
  `service-accounts` configured, nothing observable changes (every existing test passes untouched).
- A declared role: name `^[A-Z][A-Z0-9_]{1,48}$` after upper-casing, never `ADMIN`, never an allowed
  `MANAGE_ORGANIZATION` or `MANAGE_TENANTS`, in one model only. Otherwise the context refuses to start.
- Roles are ensured after objects, relationships and actions, in the same transaction, lock and `ModelOwner.run`.
- A person's change to a declared role (`PUT`, `DELETE`, permissions, field permissions) is `409`, message from
  `ModelActor.requireMayChange`. Assigning a declared role to a user or a service account is not a change.
- A realm token is a service account's only with no `email`, a non-blank `azp` and
  `preferred_username == "service-account-" + azp.lowercase()`.
- `wasichai.security.oidc.service-accounts` is an indexed list of `{client-id, roles}`, bound from environment
  variables by index (`WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_CLIENTID`, `…_0_ROLES_0`): each client id matches
  `^[a-z][a-z0-9_-]{1,48}$` and is unique in the list, roles non-empty, upper-cased, never `ADMIN`; else boot fails
  with a message naming `wasichai.security.oidc.service-accounts[i]`.
- A realm account's row: `external_client_id = azp`, `name = azp`, `secret_hash` NULL; its backing user has
  `password_hash` NULL, `enabled = false`, email `<id>@service-accounts.invalid` (`ServiceAccountSecrets.backingEmail`).
- Every refusal of a realm service-account token is a denied token (`JwtService.CLAIM_DENIED`), so a `403` with its
  reason; a wrong audience stays a `401`.
- `POST /api/auth/token` never answers `500` for a realm account's id: `401 Invalid client credentials`, one hash check.
- Every SQL statement filters by `organization_id`; schema names from `WasichaiSchemas`.
- Comments caveman style, English; `.editorconfig` (160 columns); Conventional Commits with scope `core`.
- Integration tests: Testcontainers by default. On a machine whose `DOCKER_HOST` is a remote daemon, run them in
  external-database mode (`WASICHAI_TEST_DB_*`, suite lock, `it-env.sh`), as
  [getting-started](../../development/getting-started.md#integration-tests) says; never port 5432.

## Review Focus

- A declared role naming an object the tenant does not have: the role is not created (or keeps what it had), the
  report says which grant, and the model's objects and other roles still apply — test in Task 2.
- A second start with nothing changed writes nothing, not even an admin entry, for roles too — test in Task 2.
- A person's token whose `preferred_username` is `service-account-…` but which has an `email`, or whose `azp` differs:
  the person's path, never an account — test in Task 4.
- A recreated Keycloak client (same client id, new `sub`) finds its existing row, no second row and no clash — test in
  Task 4.
- `POST /api/auth/token` with a realm account's id: `401`, not the `500` that `Rows.string` on a null hash would give
  today — test in Task 1.

---

### Task 1: Migration V23, and what reads the changed columns

**Files:**
- Create: `wasichai-core/src/main/resources/db/wasichai/core/V23__declared_roles_and_realm_service_accounts.sql`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/ServiceAccountTokens.kt` (`secretHash: String?`; a null
  hash is a mismatch after the decoy check)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/AdminService.kt` (`loadRoles` reads `declared_by`)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/AdminDtos.kt` (`RoleResponse.declaredBy`)
- Modify: `wasichai-integration-tests/src/schemaParityIt/kotlin/wasichai/it/full/SchemaParityTest.kt` (known deviations)
- Test: `wasichai-core/src/test/kotlin/wasichai/core/api/ServiceAccountApiTest.kt`

**Interfaces:**
- Produces: `roles.declared_by text NULL` with `roles_declared_by_valid CHECK (declared_by ~ '^[a-z][a-z0-9_-]{0,48}$')`;
  `service_accounts.external_client_id text NULL`; `service_accounts.secret_hash` nullable;
  `service_accounts_one_credential CHECK ((secret_hash IS NULL) <> (external_client_id IS NULL))`; unique index
  `service_accounts_org_external_client_uq ON service_accounts (organization_id, external_client_id) WHERE
  external_client_id IS NOT NULL`. `RoleResponse.declaredBy: String?` with `@field:JsonInclude(NON_NULL)`, last and
  defaulted to `null`.

- [ ] **Step 1: Write the failing test** (`ServiceAccountApiTest`):

```kotlin
// insert a users row + a service_accounts row with secret_hash NULL, external_client_id 'carga-perene' through db
@Test fun `a realm account's id at the token endpoint is the same 401 as a wrong secret`()
// POST /api/auth/token {clientId: <its id>, clientSecret: "x"} -> 401, detail "Invalid client credentials"
@Test fun `a row with both a secret and a client id is refused by the database`()    // DataIntegrityViolation
```

- [ ] **Step 2: Run it, expect FAIL** (the insert fails on `NOT NULL`):
  `./gradlew :wasichai-core:integrationTest --tests '*ServiceAccountApiTest*'`
- [ ] **Step 3: Implement** the migration (placeholders as V21/V22), the nullable hash in `ServiceAccountTokens.find`
  and `secretMatches` (`hash == null` → check the decoy, answer `false`), `declared_by` in `loadRoles` and the DTO;
  add the new lines to `SchemaParityTest` under `"ADR-031 D59"` and `"ADR-031 D60"` (Task 6 writes those entries),
  and move `service_accounts.secret_hash`'s `NOT NULL` lines to the changed ones.
- [ ] **Step 4: Run it, expect PASS**; then `./gradlew :wasichai-core:test` and
  `./gradlew :wasichai-integration-tests:schemaParityIt`.
- [ ] **Step 5: Commit** — `feat(core): V23, declared roles and realm service accounts in the schema`

### Task 2: Roles in a declared model

**Files:**
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/metadata/DeclaredModels.kt`
- Create: `wasichai-core/src/main/kotlin/wasichai/core/metadata/DeclaredModelRoles.kt` (the role type and the port)
- Create: `wasichai-core/src/main/kotlin/wasichai/core/admin/DeclaredRoles.kt` (the port's implementation)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/metadata/ModelActor.kt` (`ModelOwner.platform()`, below)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/AdminService.kt` (role methods take the platform as actor)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/autoconfigure/WasichaiPlatformAutoConfiguration.kt` (or the
  auto-configuration that builds `DeclaredModels`: pass `DeclaredRoles`)
- Test: `wasichai-core/src/test/kotlin/wasichai/core/metadata/DeclaredModelsTest.kt`

**Interfaces:**
- Produces:
  - `data class DeclaredRole(val name: String, val label: String, val ownRecordsOnly: Boolean = false,
    val permissions: List<PermissionEntry> = emptyList(), val fieldPermissions: List<FieldPermissionEntry> = emptyList())`
    in `metadata`. `PermissionEntry` and `FieldPermissionEntry` move from `admin/AdminDtos.kt` to `metadata` (same
    names, same JSON; `admin` imports them), since `metadata` may not import `admin`.
  - `DeclaredModel.roles: List<DeclaredRole> = emptyList()`, last.
  - `fun interface DeclaredModelRoles { suspend fun ensure(organizationId: UUID, model: String, roles: List<DeclaredRole>): RoleLines }`
    with `data class RoleLines(val created: List<String>, val updated: List<String>, val incompatible: List<String>)`.
  - `DeclaredModels(…, roles: DeclaredModelRoles? = null)`, last. `init` adds: names valid and not `ADMIN`, no allowed
    `MANAGE_ORGANIZATION`/`MANAGE_TENANTS`, `owners("role")`, and a model with roles but `roles == null` refuses.
    `Ensuring.run()` calls `roles?.ensure(organizationId, model.name, model.roles)` after actions and merges its lines.
  - `internal fun ModelOwner.platform(): Pair<UUID, String>?` — the run's organization and model, or null.
  - `AdminService`: a private `roleActor(): ModelActor` = the platform inside `ModelOwner.run`, else
    `currentUser.requireWithPermission(MANAGE_ORGANIZATION)` as a person's actor; `createRole`, `updateRole`,
    `setPermissions`, `setFieldPermissions` use it and audit through `ModelActor.record`. Internal additions:
    `internal suspend fun roleOrNull(organizationId: UUID, name: String): RoleResponse?`,
    `internal suspend fun setFieldPermissions(name: String, request: RoleFieldPermissionsRequest, wholeSet: Boolean)`
    (whole set: also deletes the role's rules on objects the request does not name), and
    `internal suspend fun release(organizationId: UUID, model: String, keep: Set<String>): List<String>` (clears
    `declared_by` of this model's roles not in `keep`, answers their names).
  - `class DeclaredRoles(admin: AdminService, objects: CustomObjectRepository, fields: CustomFieldRepository,
    actions: ObjectActionRepository) : DeclaredModelRoles` — per role: check every grant against the tenant first
    (missing object, field or declared action → `incompatible += "role X: <what> is not in this tenant"`, role
    skipped); another model's role → incompatible; missing → create; differs or not yet this model's → update
    (`updated += "role X"`, `"role X (taken over)"` when `declared_by` was null); then `release`. Report lines:
    `role CARGA_DATOS`, `released role OLD_ROLE`.

- [ ] **Step 1: Write the failing tests** (`DeclaredModelsTest`, real database):

```kotlin
@Test fun `a declared role is made in the tenant with its grants and field rules`()   // created ["role CARGA_DATOS"]
@Test fun `a second run writes nothing, not even an admin entry`()                   // report.unchanged, audit count same
@Test fun `a role is ensured after the objects whose grants it names`()              // object + role in one model
@Test fun `a person's role of the same name is taken over and made as declared`()    // updated ["role X (taken over)"]
@Test fun `a role naming an object the tenant lacks is left out and reported`()      // incompatible has it; objects created
@Test fun `a role removed from the model is released, with its holders`()            // declared_by null, user_roles kept
@Test fun `a declared ADMIN, a declared MANAGE_ORGANIZATION or one role in two models refuses to start`()
@Test fun `a tenant provisioned is born with the declared roles`()                   // OrganizationService.provision
@Test fun `the admin entries of a declared role have no user and say model name`()   // source "model:<name>"
```

- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-core:integrationTest --tests '*DeclaredModelsTest*'`
- [ ] **Step 3: Implement** the declarations above; `./gradlew :wasichai-core:test` keeps `CoreArchitectureTest` green
  (no `metadata` → `admin` import).
- [ ] **Step 4: Run them, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): an app declares its roles in its model`

### Task 3: Declared roles are locked through the API

**Files:**
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/AdminService.kt`
- Test: `wasichai-core/src/test/kotlin/wasichai/core/api/DeclaredRolesApiTest.kt` (pattern of `DeclaredModelApiTest`)

**Interfaces:**
- Consumes: `roleActor()`, `RoleResponse.declaredBy` (Tasks 1, 2).
- Produces: `updateRole`, `deleteRole`, `setPermissions`, `setFieldPermissions` call
  `actor.requireMayChange(role.declaredBy, "Role '$name'")` before any write. No change to `createRole` (an existing
  name is `409` already), `setUserRoles` or `ServiceAccountService`'s role assignment.

- [ ] **Step 1: Write the failing tests:**

```kotlin
@Test fun `a declared role reads with declaredBy, a person's without`()               // GET /api/roles
@Test fun `relabelling, re-granting or deleting a declared role is 409 naming the model`()
// PUT /api/roles/CARGA_DATOS, PUT …/permissions, PUT …/field-permissions, DELETE -> 409, detail contains "'test-model'"
@Test fun `an administrator still creates a role of their own and assigns a declared one`()
// POST /api/roles -> 201; PUT /api/users/{id}/roles ["CARGA_DATOS"] -> 200
@Test fun `a declared role can be given to a local service account`()                 // PUT /api/service-accounts/{id}
@Test fun `a released role can be changed again`()                                    // after the model drops it -> 200
```

- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-core:integrationTest --tests '*DeclaredRolesApiTest*'`
- [ ] **Step 3: Implement** the four checks.
- [ ] **Step 4: Run them, expect PASS**; then `./gradlew :wasichai-core:test`.
- [ ] **Step 5: Commit** — `feat(core): a person cannot change a role the app declares`

### Task 4: A realm client becomes the app's service account

**Files:**
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/platform/WasichaiIdentityProperties.kt`
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/RealmServiceAccounts.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/RealmPrincipalConverter.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/autoconfigure/WasichaiSecurityAutoConfiguration.kt` (the bean,
  only when `isOidc`, into the converter)
- Test: `wasichai-core/src/test/kotlin/wasichai/core/platform/WasichaiIdentityPropertiesTest.kt`
- Test: `wasichai-core/src/test/kotlin/wasichai/core/identity/oidc/RealmPrincipalConverterTest.kt` (a fake
  `RealmServiceAccounts`)
- Test: `wasichai-integration-tests/src/coreApiIt/kotlin/wasichai/it/full/RealmServiceAccountsTest.kt` (the SQL)
- Test: `wasichai-integration-tests/src/oidcOnly/kotlin/wasichai/it/slice/oidc/OidcServiceAccountTest.kt`

**Interfaces:**
- Produces:
  - `data class RealmServiceAccount(val clientId: String = "", val roles: List<String> = emptyList())`;
    `Oidc.serviceAccounts: List<RealmServiceAccount> = emptyList()`, last, and `Oidc.serviceAccountsByClientId:
    Map<String, RealmServiceAccount>` for lookups. `init`: each `clientId` matches `^[a-z][a-z0-9_-]{1,48}$` and is
    unique in the list, roles non-empty, none `ADMIN` after upper-casing; messages name
    `wasichai.security.oidc.service-accounts[i]`.
  - `RealmServiceAccounts` takes `configured: Map<String, RealmServiceAccount>` (= `serviceAccountsByClientId`).
  - `fun isServiceAccountToken(jwt: Jwt): String?` — the `azp` when the token is a service account's (Global
    Constraints rule), else null.
  - `sealed interface RealmAccountResolution { data class Found(val account: RealmAccount) ; data class Denied(val reason: String) }`;
    `data class RealmAccount(val id: UUID, val organizationId: UUID, val name: String, val email: String, val roles: List<String>)`.
  - `open class RealmServiceAccounts(db: DatabaseClient, schemas: WasichaiSchemas, configured: Map<String, RealmServiceAccount>,
    lock: ClusterLock, audit: AdminAudit, transactions: TransactionalOperator, cache: Duration, clock: Clock = Clock.systemUTC())`
    with `open suspend fun resolve(organizationId: UUID, clientId: String): RealmAccountResolution`:
    not configured → `Denied("client <id> is not a service account of this app")`; a row by
    `(organization_id, external_client_id)`, else, under `withXactLock("wasichai:realm-service-account:<org>:<id>")`
    in one transaction, a local row with that name → `Denied("a local service account of this organization is already
    named <id>")`, else insert the backing user and the row (platform audit entry, source `realm:<id>` via
    `ChangeOrigin.within`); a configured role missing in the organization → `Denied("role <R> of client <id> is not in
    this organization")`, nothing written; `user_roles` replaced when they differ from the configuration (audited);
    disabled → `Denied("service account <name> is disabled in this app")`. Found results cached per
    `(organizationId, clientId)` for `cache` (`wasichai.security.oidc.roles-cache`), same sweep as `RealmUsers`.
  - `RealmPrincipalConverter(realmIssuer, users, accounts: RealmServiceAccounts)`: after `organizationAlias` and the
    slug lookup, `isServiceAccountToken(jwt)?.let { accounts.resolve(organizationId, it) }`; `Found` → claims `sub` =
    id, `org`, `email`, `roles`, `JwtService.CLAIM_SERVICE_ACCOUNT` = name; `Denied` → `denied(jwt, reason)`.

- [ ] **Step 1: Write the failing unit tests:**

```kotlin
// WasichaiIdentityPropertiesTest
@Test fun `a service-account client id that is no account name is refused`()          // "Carga Ica" -> message names [0]
@Test fun `a client id twice in the list is refused`()                                 // message names both indexes
@Test fun `a service-account client with no role, or with ADMIN, is refused`()
// Binder over a MapConfigurationPropertySource with SystemEnvironmentPropertySource names, as the kit sets them:
// WASICHAI_SECURITY_OIDC_SERVICEACCOUNTS_0_CLIENTID=srtm-catastro-muni-ica, …_0_ROLES_0=SRTM_LECTOR,
// …_0_ROLES_1=CATASTRO_LECTOR, …_1_CLIENTID=carga-muni-ica, …_1_ROLES_0=CARGA_DATOS
@Test fun `the list binds from environment variables by index, dashes in the client id kept`()
// RealmPrincipalConverterTest
@Test fun `a client token of a configured client is the app's service account`()     // claims sub, org, roles, service_account
@Test fun `a token with an email is a person's even when its username looks like a service account`()
@Test fun `a username that does not match azp is a person's`()                        // azp "x", preferred_username service-account-y
@Test fun `a client the app does not list is a denial that says so`()
@Test fun `a client token with no organization is the same denial a person gets`()
```

- [ ] **Step 2: Write the failing SQL tests** (`RealmServiceAccountsTest`, real database, `cache = 0`):

```kotlin
@Test fun `the first token of a configured client makes its row, backing user and roles`()  // secret_hash null, password_hash null
@Test fun `two first tokens at once make one row`()                                         // two concurrent resolve -> one row
@Test fun `a recreated client with the same id finds its row`()                             // resolve twice, same id
@Test fun `a role the organization lacks writes nothing and says which`()
@Test fun `roles follow the configuration`()                                                // other configured map -> user_roles replaced
@Test fun `a local account with the client's name is not adopted`()
@Test fun `a disabled row is denied`()
@Test fun `the same client in another organization is another row`()
```

- [ ] **Step 3: Write the failing end-to-end tests** (`OidcServiceAccountTest` extends `OidcIntegrationTest`; its
  `@TestPropertySource` adds `wasichai.security.oidc.service-accounts[0].client-id=carga-perene` and `…[0].roles=CARGA_DATOS`; a
  `@TestConfiguration` `DeclaredModel("oidc-it", objects = emptyList(), roles = listOf(CARGA_DATOS with organization-wide
  READ, CREATE, MANAGE_METADATA))`, ensured for the test organization in `@BeforeEach`; a helper
  `clientToken(clientId, organization = listOf(ORGANIZATION), aud = listOf(CLIENT_ID))` signs RS256 with `azp`,
  `preferred_username = "service-account-$clientId"`, a random `sub` and no `email`):

```kotlin
@Test fun `a configured client reaches the api as a service account with its role`()   // /api/auth/me -> serviceAccount "carga-perene", roles ["CARGA_DATOS"]
@Test fun `it may define objects but never administer the tenant`()                    // POST /api/objects -> 201; GET /api/users -> 403
@Test fun `a client the app does not list gets 403 saying so`()
@Test fun `a client token for another app is 401`()                                    // aud ["srtm"]
@Test fun `a client token for an organization the app does not have is 403`()
@Test fun `a person still signs in as before`()                                        // invite + realmToken -> 200, no serviceAccount
```

- [ ] **Step 4: Run them, expect FAIL** — `./gradlew :wasichai-core:test --tests '*WasichaiIdentityPropertiesTest*'
  --tests '*RealmPrincipalConverterTest*'`, `./gradlew :wasichai-integration-tests:coreApiIt --tests
  '*RealmServiceAccountsTest*'`, `./gradlew :wasichai-integration-tests:oidcOnly`.
- [ ] **Step 5: Implement** the declarations above and the wiring.
- [ ] **Step 6: Run them, expect PASS**; then `./gradlew build` and `./gradlew integrationTest` (every suite untouched).
- [ ] **Step 7: Commit** — `feat(core): a realm client is the app's service account, in the token's organization`

### Task 5: Realm accounts in the administration, read-only

**Files:**
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/ServiceAccountAdmin.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/autoconfigure/WasichaiPlatformAutoConfiguration.kt` (hand the
  configured client ids to `ServiceAccountService`)
- Test: `wasichai-integration-tests/src/oidcOnly/kotlin/wasichai/it/slice/oidc/OidcServiceAccountTest.kt`
- Test: `wasichai-core/src/test/kotlin/wasichai/core/api/ServiceAccountApiTest.kt` (local unchanged)

**Interfaces:**
- Consumes: the row Task 4 makes; `Oidc.serviceAccountsByClientId.keys`.
- Produces: `ServiceAccountResponse.externalClientId: String?` (`NON_NULL`, last); `secretRotatedAt` null for a realm
  account. `ServiceAccountService(…, configuredClients: Set<String> = emptySet())`: on a realm account `update` with
  `roles` → `ConflictException("Roles of a realm service account come from wasichai.security.oidc.service-accounts")`;
  `rotateSecret` → `ConflictException("A realm service account has no secret here: rotate it in the identity provider")`;
  `delete` while its client id is configured → `ConflictException("Client <id> is still configured: it would come back
  on its next token")`. `update` with `enabled` alone works as for a local account.

- [ ] **Step 1: Write the failing tests** (in `OidcServiceAccountTest`, as an invited `ADMIN`):

```kotlin
@Test fun `a realm account is listed with its client id and no secret date`()
@Test fun `its roles, its secret and its deletion are refused while configured`()   // PUT roles, POST …/secret, DELETE -> 409
@Test fun `an administrator disables it and its next token is 403`()                // PUT {enabled:false}; /api/auth/me -> 403
```

  and in `ServiceAccountApiTest`: `a local account's answer has no externalClientId`.
- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-integration-tests:oidcOnly`
- [ ] **Step 3: Implement** the field, the three refusals and the wiring.
- [ ] **Step 4: Run them, expect PASS**; then `./gradlew :wasichai-core:integrationTest --tests '*ServiceAccountApiTest*'`.
- [ ] **Step 5: Commit** — `feat(core): realm service accounts are read-only in the administration`

### Task 6: Documentation and history

**Files:**
- Modify: `docs/security/authentication.md` («Service accounts»: a subsection «Signed by the domain's identity
  provider» with the token shape, the configuration, the row by client id, the `403` reasons, revocation; «Signing in
  through the domain's identity provider» links to it)
- Modify: `docs/api/rest.md` (`RoleResponse.declaredBy` and the `409`s on declared roles;
  `ServiceAccountResponse.externalClientId` and the `409`s on realm accounts)
- Modify: `docs/guides/build-your-app.md` (declared roles next to the declared model, one example)
- Modify: `docs/adr/0031-deliberate-deviations-from-sapgis.md` (new entries D59, declared roles locked, and D60, realm
  service accounts; each with its ADR and tests, as D57)
- Modify: `docs/HISTORY.md` (two entries dated 2026-11-xx, newest first)
- Modify: `docs/manuals/en/reference/configuration.adoc` and `docs/manuals/es/reference/configuration.adoc`
  (`wasichai.security.oidc.service-accounts[i].client-id` and `.roles`, with the environment-variable form by index)

- [ ] **Step 1: Write the docs** from the Interfaces blocks above; no new behaviour.
- [ ] **Step 2: Check** every line is at most 160 columns, `yarn format:check`, and the manuals job locally if it has
  a script, else CI.
- [ ] **Step 3: Commit** — `docs(core): declared roles and realm service accounts`
