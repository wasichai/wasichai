# Single sign-on with Keycloak, backend: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** an `oidc` identity mode in `wasichai-core`, where people sign in through the domain's Keycloak realm and each
app keeps its users, roles and permissions; `local` mode stays exactly as it is.

**Architecture:** a decoder that routes by `iss` (the realm's RS256 tokens for people, the app's HS256 tokens for its
service accounts), and an authentication converter that turns a realm token into a token with wasichai's own claims
(`sub` = `users.id`, `org`, `email`, `roles` from the database). `CurrentUser`, `AuthenticatedUser` and every
permission check keep reading those claims and do not change. Users are invited by email and linked to the realm's
`sub` on their first sign-in.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1 WebFlux, Spring Security resource server (Nimbus, already on the classpath
through `spring-boot-starter-oauth2-resource-server`), R2DBC `DatabaseClient`, Flyway, JUnit 5, Testcontainers.

**Spec:** [docs/superpowers/specs/2026-11-16-sso-keycloak-design.md](../specs/2026-11-16-sso-keycloak-design.md),
[ADR-084](../../adr/0084-people-sign-in-through-the-domains-identity-provider.md).

## Global Constraints

- `wasichai.security.identity` is `local` or `oidc`, **`local` by default**; with `local` nothing observable changes
  (every existing test passes untouched).
- `oidc` needs `wasichai.security.oidc.issuer-uri` and `wasichai.security.oidc.client-id`, both non-blank; the client
  id is also the required audience. Boot fails otherwise.
- `oidc` together with `wasichai.seed.dev=true` fails at boot.
- A realm token is accepted only with `iss` equal to `issuer-uri`, `aud` containing `client-id`, RS256, valid
  timestamps. A local token keeps today's checks (HS256, revocation when on). Any other `iss`: 401.
- The token's `organization` claim must name exactly one organization; its alias is `organizations.slug`.
- A person enters only with a `users` row of that organization: linked by `external_subject`, or linked on this
  sign-in by `lower(email)` when `email_verified` is `true` and the row has no `external_subject` yet.
- Roles of a realm user come from `user_roles`, through a per-user cache of `wasichai.security.oidc.roles-cache`
  (default `5s`).
- In `oidc`: `POST /api/auth/login` and `POST /api/auth/logout` answer 404; a user or an organization admin is created
  without a password, and a password in a request is a 400. `POST /api/auth/token` (service accounts) is unchanged.
- `GET /api/auth/config` is public: `{"mode":"local"}` or `{"mode":"oidc","issuer":…,"clientId":…}`.
- Comments caveman style, English; `.editorconfig`; Conventional Commits with scope `core`.

## Review Focus

- A realm token whose `organization` claim is a map (`{"perene": {"id": "…"}}`, Keycloak's "add organization id"
  option) instead of a list: it must resolve the same alias — test in Task 4.
- Two people of the same organization share no email, but the same email exists in two organizations: linking must
  only look inside the token's organization — test in Task 4.
- An already linked row whose email changed in Keycloak: found by `sub`, its `email` updated, not linked again — test
  in Task 4.
- A token of the realm for another client (another app of the domain): 401, never a user — test in Task 3.
- A local login against a user created without a password in `oidc` and later switched to `local`: invalid
  credentials, never a 500 — test in Task 2.

---

### Task 1: Identity mode and its properties

**Files:**
- Create: `wasichai-core/src/main/kotlin/wasichai/core/platform/WasichaiIdentityProperties.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/autoconfigure/WasichaiPlatformAutoConfiguration.kt` (register
  the properties next to the others; the seed bean refuses `oidc`)
- Test: `wasichai-core/src/test/kotlin/wasichai/core/platform/WasichaiIdentityPropertiesTest.kt`

**Interfaces:**
- Produces: `enum class IdentityMode { LOCAL, OIDC }`;
  `@ConfigurationProperties("wasichai.security") data class WasichaiIdentityProperties(val identity: IdentityMode = LOCAL, val oidc: Oidc = Oidc())`
  with `data class Oidc(val issuerUri: String = "", val clientId: String = "", val jwkSetUri: String = "", val rolesCache: Duration = Duration.ofSeconds(5))`
  and `val isOidc: Boolean`; `fun Oidc.jwkSetUriOrDefault(): String` = `jwkSetUri` or
  `"${issuerUri.trimEnd('/')}/protocol/openid-connect/certs"` (Keycloak's path).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `local by default, with no oidc settings needed`() {
    assertThat(WasichaiIdentityProperties().isOidc).isFalse()
}
@Test fun `oidc refuses a blank issuer or client id`() {
    assertThatThrownBy { WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = "", clientId = "caja")) }
        .hasMessageContaining("wasichai.security.oidc.issuer-uri")
    val realm = "https://auth.example.org/realms/peru"
    assertThatThrownBy { WasichaiIdentityProperties(IdentityMode.OIDC, Oidc(issuerUri = realm, clientId = " ")) }
        .hasMessageContaining("wasichai.security.oidc.client-id")
}
@Test fun `the jwk set defaults to keycloak's path under the issuer`() {
    assertThat(Oidc(issuerUri = "https://auth.example.org/realms/peru/").jwkSetUriOrDefault())
        .isEqualTo("https://auth.example.org/realms/peru/protocol/openid-connect/certs")
}
@Test fun `a negative roles cache is refused`() { /* Duration.ofSeconds(-1) -> message names wasichai.security.oidc.roles-cache */ }
```

Plus, in the existing `WasichaiApplicationTest` style (an `ApplicationContextRunner` over the platform
auto-configuration): `wasichai.security.identity=oidc` + `wasichai.seed.dev=true` fails with a message naming both
properties.

- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-core:test --tests '*WasichaiIdentityPropertiesTest*'`
- [ ] **Step 3: Implement** the properties with `init { require(...) }` checks (pattern of `JwtProperties`), and make
  `wasichaiCoreSeedMigration` take `WasichaiIdentityProperties` and `require(!it.isOidc)`.
- [ ] **Step 4: Run them, expect PASS**, then `./gradlew :wasichai-core:test`.
- [ ] **Step 5: Commit** — `feat(core): wasichai.security.identity, local or oidc`

### Task 2: Users without a password, linked to an external subject

**Files:**
- Create: `wasichai-core/src/main/resources/db/wasichai/core/V22__external_subject.sql`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/User.kt` (`passwordHash: String?`,
  `externalSubject: String?`; `findByEmail` reads both)
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/AuthService.kt` (a null hash is invalid credentials)
- Test: `wasichai-integration-tests/src/coreApiIt/kotlin/wasichai/it/full/PasswordlessUserLoginTest.kt`

**Interfaces:**
- Produces: column `users.external_subject text NULL`, unique index
  `users_org_external_subject_uq ON users (organization_id, external_subject) WHERE external_subject IS NOT NULL`;
  `users.password_hash` nullable. `User.passwordHash: String?`, `User.externalSubject: String?`.

- [ ] **Step 1: Write the failing test** — `a user without a password cannot sign in locally, and gets the same answer as a wrong password`:
  insert a user with `password_hash NULL` through `db`, `POST /api/auth/login` with any password → `401`, body equal
  to the one a wrong password gets (`Invalid email or password`).
- [ ] **Step 2: Run it, expect FAIL** (the insert fails on `NOT NULL`) — `./gradlew :wasichai-integration-tests:coreApiIt --tests '*PasswordlessUserLoginTest*'`
- [ ] **Step 3: Implement** the migration (`ALTER TABLE ${schema}.users ALTER COLUMN password_hash DROP NOT NULL`,
  the column, the partial unique index; follow V17's placeholders) and the null check in `authenticate`, before
  BCrypt.
- [ ] **Step 4: Run it, expect PASS**; then `./gradlew :wasichai-core:test`.
- [ ] **Step 5: Commit** — `feat(core): users without a local password, and their external subject`

### Task 3: A decoder for the realm's tokens and the app's own

**Files:**
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/RealmJwtDecoder.kt`
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/IssuerRoutingJwtDecoder.kt`
- Test: `wasichai-core/src/test/kotlin/wasichai/core/identity/oidc/IssuerRoutingJwtDecoderTest.kt`

**Interfaces:**
- Consumes: `WasichaiIdentityProperties.Oidc` (Task 1).
- Produces:
  - `class RealmJwtDecoder(val issuer: String, val decoder: ReactiveJwtDecoder)` — a holder type, like
    `WasichaiJwtKey`, so a test can replace it without replacing the app's decoder.
  - `fun realmValidator(issuer: String, clientId: String): OAuth2TokenValidator<Jwt>` — default timestamps, issuer,
    and `aud` contains `clientId`.
  - `fun realmJwtDecoder(oidc: Oidc): RealmJwtDecoder` — `NimbusReactiveJwtDecoder.withJwkSetUri(oidc.jwkSetUriOrDefault())`
    (lazy: no call to Keycloak at boot), RS256, `realmValidator`.
  - `class IssuerRoutingJwtDecoder(realm: RealmJwtDecoder, local: ReactiveJwtDecoder, localIssuer: String) : ReactiveJwtDecoder`
    — reads `iss` without verifying (`JWTParser.parse(token).jwtClaimsSet.issuer`), hands the token to the matching
    decoder, and fails with `BadJwtException` for any other or unreadable token.

- [ ] **Step 1: Write the failing tests.** Fixtures: an RSA key generated per test class, a realm decoder built with
  `NimbusReactiveJwtDecoder.withPublicKey(key.public)` plus `realmValidator("https://auth.example.org/realms/peru", "caja")`,
  and today's HS256 decoder over a 32-byte test secret.

```kotlin
@Test fun `a realm token for this client is accepted`()                     // iss realm, aud ["caja"], RS256 -> decodes, subject kept
// aud ["srtm"] -> BadJwtException / JwtValidationException
@Test fun `a realm token for another client of the realm is refused`()
@Test fun `a token signed by another key under the realm's issuer is refused`()
@Test fun `the app's own service-account token still decodes`()             // iss "wasichai", HS256 -> decodes
@Test fun `an unknown issuer is refused before any key is tried`()          // iss "https://evil.example" -> BadJwtException
@Test fun `garbage is refused`()                                            // "not-a-jwt" -> BadJwtException
```

- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-core:test --tests '*IssuerRoutingJwtDecoderTest*'`
- [ ] **Step 3: Implement** the four declarations above.
- [ ] **Step 4: Run them, expect PASS.**
- [ ] **Step 5: Commit** — `feat(core): decode the realm's tokens next to the app's own`

### Task 4: From a realm token to the app's user

**Files:**
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/RealmUsers.kt`
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/oidc/RealmPrincipalConverter.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/CurrentUser.kt` (the denial claim, below)
- Test: `wasichai-core/src/test/kotlin/wasichai/core/identity/oidc/RealmPrincipalConverterTest.kt` (with a fake
  `RealmUsers`)
- Test: `wasichai-integration-tests/src/coreApiIt/kotlin/wasichai/it/full/RealmUsersTest.kt` (the SQL)

**Interfaces:**
- Consumes: `RealmJwtDecoder.issuer` (Task 3), `RoleQueries.roleNamesOf(userId)`, `User` (Task 2).
- Produces:
  - `fun organizationAlias(jwt: Jwt): Result<String>` — the `organization` claim as a list of aliases or as a map keyed
    by alias; failure messages `no organization in the token: sign in to one` and
    `several organizations in the token: sign in to one`.
  - `open class RealmUsers(db: DatabaseClient, schemas: WasichaiSchemas, rolesCache: Duration)` with
    `suspend fun organizationIdBySlug(slug: String): UUID?`,
    `suspend fun resolve(organizationId: UUID, subject: String, email: String?, emailVerified: Boolean, displayName: String?): RealmUser?`
    and `suspend fun roles(userId: UUID): List<String>` (cached per user for `rolesCache`, same shape as
    `TokenRevocation`'s cache). `resolve` finds by `(organization_id, external_subject)`; else, when `emailVerified`,
    by `(organization_id, lower(email))` among rows with `external_subject IS NULL`, and writes the subject; then
    updates `email`/`display_name` when the token's differ. `data class RealmUser(val id: UUID, val organizationId: UUID, val email: String, val enabled: Boolean)`.
  - `class RealmPrincipalConverter(realmIssuer: String, users: RealmUsers) : Converter<Jwt, Mono<AbstractAuthenticationToken>>`
    — for a realm token returns a `JwtAuthenticationToken` over a new `Jwt` with the same token value, headers, `iat`
    and `exp`, and claims `sub` = `users.id`, `org`, `email`, `roles` (the names `JwtService` already uses); for any
    other issuer, the token as is.
  - `JwtService.CLAIM_DENIED = "wasichai_denied"`: when the person has no access, the converter still authenticates,
    with this claim holding the reason, and `CurrentUser.require()` throws `ForbiddenException(reason)`. Reasons:
    `organization <alias> is not in this app`, `you have no access to this app; ask its administrator`,
    `your account in this app is disabled`, and the two of `organizationAlias`.

- [ ] **Step 1: Write the failing unit tests** (`RealmPrincipalConverterTest`):

```kotlin
@Test fun `an invited person is the app's user, with the app's roles`()      // claims sub = users.id, org, email, roles ["CAJERO"]
@Test fun `the organization claim may be a map keyed by alias`()             // {"perene": {"id": "x"}} -> same org
@Test fun `no organization, or two, is a denial that says which`()
@Test fun `an organization the app does not have is a denial`()
@Test fun `a person who was not invited is a denial, not an empty user`()
@Test fun `a disabled row is a denial`()
@Test fun `a service-account token passes through untouched`()               // iss = local issuer -> same Jwt
```

and in `CurrentUserTest`: `a denied token is a 403 with its reason`.

- [ ] **Step 2: Write the failing SQL tests** (`RealmUsersTest`, real database):

```kotlin
@Test fun `the first sign-in with a verified email links the row`()          // external_subject written
@Test fun `an unverified email links nothing`()                              // resolve -> null, row untouched
@Test fun `a linked row is found by subject even after its email changed`()  // email updated, no second link
@Test fun `the same email in another organization is never linked`()
@Test fun `a role removed shows after the cache`()                           // rolesCache = 0 in this test
```

- [ ] **Step 3: Run both, expect FAIL.**
- [ ] **Step 4: Implement** `RealmUsers`, `organizationAlias`, `RealmPrincipalConverter` and the denial in
  `CurrentUser.require()`.
- [ ] **Step 5: Run both, expect PASS**; `./gradlew :wasichai-core:test`.
- [ ] **Step 6: Commit** — `feat(core): a realm token becomes the app's user, with the app's roles`

### Task 5: Wire the oidc mode, and what it switches off

**Files:**
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/autoconfigure/WasichaiSecurityAutoConfiguration.kt`
- Create: `wasichai-core/src/main/kotlin/wasichai/core/identity/AuthConfigController.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/identity/AuthController.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/admin/AdminDtos.kt`, `AdminService.kt`
- Modify: `wasichai-core/src/main/kotlin/wasichai/core/organization/OrganizationController.kt`, `OrganizationService.kt`
- Test: a new slice suite `oidcOnly` in `wasichai-integration-tests` (register it in `sliceSuites` with no starters):
  `src/oidcOnly/kotlin/wasichai/it/slice/oidc/{OidcOnlyApplication.kt, OidcIntegrationTest.kt, OidcSignInTest.kt}`

**Interfaces:**
- Consumes: everything above.
- Produces:
  - beans, only when `isOidc`: `RealmJwtDecoder` (`@ConditionalOnMissingBean`), the `jwtDecoder` becomes
    `IssuerRoutingJwtDecoder(realm, <today's decoder, revocation included>, JwtProperties.issuer)`, `RealmUsers`,
    `RealmPrincipalConverter`; the security chain sets `jwtAuthenticationConverter` to it and permits
    `GET /api/auth/config` in both modes.
  - `GET /api/auth/config` → `AuthConfig(mode: String, issuer: String? = null, clientId: String? = null)`, nulls left
    out of the JSON; `mode` is `"local"` or `"oidc"`.
  - In `oidc`: `AuthController.login` and `logout` throw `NotFoundException`; `CreateUserRequest.password` and
    `CreateOrganizationRequest.adminPassword` become `String?` (`@field:NotBlank` moves into the service, local mode
    only) and a password given in `oidc` is a `ValidationException` on the field `password` / `adminPassword` with
    `passwords live in the identity provider`; `UpdateUserRequest.password` likewise. The password policy runs only in
    `local`.
- `OidcIntegrationTest`: its own `@SpringBootTest(RANDOM_PORT)` with `WasichaiTestDatabase` properties,
  `wasichai.seed.dev=false`, `wasichai.security.identity=oidc`, issuer `https://auth.example.org/realms/peru`, client
  `caja`, `roles-cache=0s`, and a `@TestConfiguration` `RealmJwtDecoder` over a test RSA key (`withPublicKey`). A
  helper `realmToken(sub, email, organization = listOf("perene"), aud = listOf("caja"), emailVerified = true)` signs
  RS256. The organization, its `ADMIN` role and the invited users are inserted with SQL.

- [ ] **Step 1: Write the failing tests** (`OidcSignInTest`):

```kotlin
@Test fun `the config tells the ui to sign in through the realm`()            // {"mode":"oidc","issuer":…,"clientId":"caja"}, no auth
@Test fun `an invited person reaches the api as the app's user`()             // GET /api/auth/me -> userId = users.id, roles ["ADMIN"]
// POST /api/users {email, displayName, roles} -> 201; then that email's token -> 200 on /api/auth/me
@Test fun `an administrator invites without a password, and the invitee then signs in`()
@Test fun `a password in an invitation is refused`()                          // 400 on field password
@Test fun `a person signed in to the realm but not invited gets 403 saying so`()
@Test fun `a token of the realm for another app is 401`()                     // aud ["srtm"]
@Test fun `local sign-in and sign-out are not there`()                        // POST /api/auth/login, /api/auth/logout -> 404
@Test fun `a service account of the app still gets and uses its token`()      // POST /api/auth/token -> token; GET /api/auth/me with it -> serviceAccount set
```

and in an existing local-mode suite (`coreApiIt`): `the config says local` (`{"mode":"local"}`).

- [ ] **Step 2: Run them, expect FAIL** — `./gradlew :wasichai-integration-tests:oidcOnly`
- [ ] **Step 3: Implement** the wiring, the config endpoint and the password switches.
- [ ] **Step 4: Run them, expect PASS**; then the whole build: `./gradlew build integrationTest` (every existing suite
  must pass untouched: `local` is the default).
- [ ] **Step 5: Commit** — `feat(core): oidc mode, people sign in through the realm`

### Task 6: Documentation and history

**Files:**
- Modify: `docs/security/authentication.md` (a section «Signing in through the domain's identity provider»: the
  switch and its properties, the two issuers, the organization alias = slug, invite and link, what `oidc` turns off,
  and the 403 reasons; «Development seed» notes it refuses `oidc`)
- Modify: `docs/api/rest.md` (`GET /api/auth/config`; `login`/`logout` 404 in `oidc`; passwords optional on
  `POST /api/users` and `POST /api/organizations`)
- Modify: `docs/HISTORY.md` (an entry for 2026-11-xx, newest first)
- Modify: the manuals' configuration reference (`docs/manuals/*/reference/configuration.adoc`, both languages): the
  four `wasichai.security.*` properties

- [ ] **Step 1: Write the docs** from the Interfaces blocks above; no new behaviour.
- [ ] **Step 2: Check** `yarn format:check` and the manuals job locally if it has a script (`docs/manuals`), else CI.
- [ ] **Step 3: Commit** — `docs(core): signing in through the domain's identity provider`
