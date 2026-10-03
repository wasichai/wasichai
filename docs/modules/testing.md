# Testing your app

## Backend: wasichai-test

```kotlin
testImplementation("wasichai:wasichai-test")
```

`WasichaiIntegrationTest` (`wasichai.test.WasichaiIntegrationTest`) is the base class for API tests against a real
PostgreSQL. It is tagged `@Tag("integration")` and configured `@SpringBootTest(webEnvironment = RANDOM_PORT)` with
`@AutoConfigureWebTestClient(timeout = "30s")`; a `@TestPropertySource` turns on `wasichai.seed.dev=true` (so the
seeded `admin@wasichai.local` / `admin` user exists), sets a fixed `wasichai.security.jwt.secret` and binds the server
to `127.0.0.1` (`server.address`), the address the client's `localhost` reaches: a server on every address shares its
port with any process that binds `127.0.0.1` to it (macOS allows that), and that process then answers the tests
(issue #34). The app under
test is the `@SpringBootConfiguration` found above the test's own package — give the test one with
`@EnableAutoConfiguration` and no component scan, the same way a real app boots with wasichai. A subclass may
override any property with its own `@TestPropertySource` (for example, other schema names).

The base class does not add `@ActiveProfiles("test")`: a profile literally named `test` can switch some
libraries' beans off without a word (Embabel's agents, for one), so `WasichaiIntegrationTest` avoids it.

It gives a subclass:

- `client: WebTestClient` — autowired, ready to call the app's routes.
- `uniqueName(prefix = "obj")` — the test database is shared across the whole suite, so every test names its own
  object/record with a random suffix instead of a fixed name.
- `bearer()` — logs in as the seeded admin (`ADMIN_EMAIL` / `ADMIN_PASSWORD`) via `POST /api/auth/login` and
  returns the `Authorization` header value (`"Bearer <token>"`). A login that does not answer `200` fails with the
  whole exchange (URL, headers, body), so a failure at this shared step says who answered.
- `bearer(email, password)` — the same, for a test that needs a token that is not the seeded administrator's (for
  example, one that exercises permission enforcement).

The `integration` tag keeps these tests out of `./gradlew build` / `test`, so a machine without a usable docker
daemon still gets a green build. Run them with `./gradlew integrationTest`: the `wasichai.integration-test` Gradle
convention plugin (`build-logic/src/main/kotlin/wasichai.integration-test.gradle.kts`) registers an
`integrationTest` task per module that runs only tests tagged `integration`
(`useJUnitPlatform { includeTags("integration") }`), runs after the plain `test` task, and does not fail when a
module has none. An app outside this repo without the convention plugin needs the plain equivalent: a `Test` task
that includes the `integration` tag.

**Database.** `WasichaiTestDatabase` (`wasichai.test.WasichaiTestDatabase`) is the one database an integration-test JVM
shares across all its tests; `WasichaiIntegrationTest` wires its connection properties in automatically through a
`@DynamicPropertySource`. By default it starts a Testcontainers `postgres:18` container. A module whose tests need
PostGIS sets the image on its own `integrationTest` task —
`tasks.integrationTest { systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6") }` — which
`WasichaiTestDatabase` reads before starting the container. Setting `WASICHAI_TEST_DB_HOST` switches every suite in the
JVM to an already-running external database instead (needed when Testcontainers can't reach a remote docker
daemon's published ports); once it is set, `WASICHAI_TEST_DB_PORT`, `WASICHAI_TEST_DB_NAME`, `WASICHAI_TEST_DB_USERNAME`
and `WASICHAI_TEST_DB_PASSWORD` are all required — there is no safe default for a database the suite wipes. The
database name must end in `_test`, or the suite refuses to touch it. The external database is wiped once per JVM,
before the first test context, behind a Postgres session advisory lock (`WasichaiTestDatabase.SUITE_LOCK`) so two
JVMs sharing one external database serialize instead of wiping each other mid-run. See
[../development/getting-started.md#integration-tests](../development/getting-started.md#integration-tests) for the
full external-database walkthrough: the tunnel variables, the git-ignored `it-env.sh` helper and the leftover-table
recovery recipe.

`WasichaiContextRunner` (`wasichai.test.WasichaiContextRunner`) is for auto-configuration tests that prove a module's
auto-config wires, backs off and joins core's SPIs, without a database. `WasichaiContextRunner.core()` returns a
`ReactiveWebApplicationContextRunner` pre-loaded with core's auto-configurations plus Spring's reactive security
auto-configurations, a mocked `DatabaseClient` bean, a plain `JsonMapper` bean, migrations turned off
(`wasichai.database.migrate=false`) and a fixed test JWT secret; a module test adds its own auto-configuration with
`.withConfiguration(...)` and asserts on the resulting context. `wasichai-test` depends on `wasichai-core` only as
`compileOnly`, so `wasichai-test` itself carries no dependency edge to `wasichai-core` in its published POM — a
consumer that calls `WasichaiContextRunner.core()` must put `wasichai-core` (or any wasichai starter, which already
bundles it) on its own test classpath.

## Frontend: @wasichai/testing

Peer dependencies: `@wasichai/core`, `@tanstack/react-query`, `@testing-library/react`, `react`, `react-dom`,
`react-router`.

`renderWithProviders(ui, options)` mounts the same providers `WasichaiApp` does — registry, api client, i18n,
react-query, auth — around a `MemoryRouter`. `coreModule` is registered automatically, the same way `WasichaiApp`
registers it: the caller's `modules` list is prepended with `coreModule` unless it is already present, so every
module's tests get core's routes and nav groups without naming it, and passing `coreModule` explicitly (to
reorder it) never registers it twice. Other options: `config` (a partial `WasichaiConfig`; `storagePrefix` defaults
to `wasichai-test`), `route` (the initial URL, default `/`), `path` (a route pattern so `useParams()` resolves),
`user` (`undefined` = the signed-in `TEST_USER`, `null` = signed out), `permissions` (`undefined` = admin
`TEST_PERMISSIONS`, `null` = none) and `language` (`undefined` = the first configured language). It returns
Testing Library's `RenderResult` plus the `queryClient` it built.

`mockFetch(routes, { baseUrl })` swaps `globalThis.fetch` for a table of canned routes (default `baseUrl`:
`/api`). The first route whose method and path match wins — a string path matches the request path without its
query string, a `RegExp` is tested against path plus query. A request that matches nothing answers a `404`
problem body naming the method and path, so a test that forgot to mock a route fails on it instead of hanging.
`mockFetch` returns `{ calls, restore }`: `calls` records every request (method, url, path relative to the base
url, parsed body), and `restore()` puts the original `fetch` back. `jsonResponse(body, status)` builds a JSON
`Response` for a route's `body`.

Consumers must run Testing Library's cleanup between tests — `renderWithProviders` mounts a real `AuthProvider`,
and only unmounting the previous tree (not just rendering a new one) runs its effect cleanup, which clears
`onUnauthorized` on the api client. Either turn on vitest's `test.globals: true` (which enables Testing Library's
own `afterEach` auto-cleanup) or call `afterEach(cleanup)` in a setup file.

## Example

Backend, from `wasichai-core/src/test/kotlin/wasichai/core/api/CoreOnlyApiTest.kt`:

```kotlin
class CoreOnlyApiTest : WasichaiIntegrationTest() {
    @BeforeEach
    fun createObject() {
        token = bearer()
        objectName = uniqueName("flat")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to objectName, "label" to "Flat", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }
}
```

Frontend, from `packages/testing/src/render.test.tsx` in wasichai-ui:

```tsx
it('takes modules, a signed-out caller and a language, with coreModule registered automatically', () => {
  renderWithProviders(<Who />, { modules: [{ id: 'gis' }], user: null, language: 'en' })
  expect(screen.getByText('nobody Save core,gis')).toBeInTheDocument()
})
```
