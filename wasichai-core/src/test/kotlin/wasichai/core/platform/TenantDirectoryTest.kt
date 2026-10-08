package wasichai.core.platform

import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import reactor.core.publisher.Mono
import reactor.util.context.Context

// ADR-057: the tenant list is for background work. a request, with a token or anonymous, never gets it,
// and the database is not even asked. the listing itself is TenantDirectoryApiTest's (it needs a database)
class TenantDirectoryTest {
    private val db = mock(DatabaseClient::class.java)
    private val directory = DatabaseTenantDirectory(db, WasichaiSchemas("wasichai", "app_data"))

    private val authenticated = ReactiveSecurityContextHolder.withAuthentication(TestingAuthenticationToken("someone", "n/a", "ROLE_ADMIN"))

    // what the security web filter gives an anonymous request: the key, with no authentication behind it
    private val anonymous = ReactiveSecurityContextHolder.withSecurityContext(Mono.empty<SecurityContext>())

    @Test
    fun `inside a request with a token both calls throw before touching the database`() =
        runTest {
            assertRefused(authenticated)
        }

    @Test
    fun `inside an anonymous request both calls throw too`() =
        runTest {
            assertRefused(anonymous)
        }

    @Test
    fun `the tripwire tells a request from background work by the security context key`() {
        assertThat(Background.isRequest(Context.empty())).isFalse()
        assertThat(Background.isRequest(Context.of("something", "else"))).isFalse()
        assertThat(Background.isRequest(authenticated)).isTrue()
        assertThat(Background.isRequest(anonymous)).isTrue()
    }

    private suspend fun assertRefused(request: Context) {
        withContext(request.asCoroutineContext()) {
            listOf(
                runCatching { directory.organizations() },
                runCatching { directory.organizationsWithObject("predio") }
            ).forEach {
                assertThat(it.exceptionOrNull())
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("never lists the tenants")
            }
        }
        verifyNoInteractions(db)
    }
}
