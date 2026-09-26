package wasichai.it.slice.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import wasichai.agent.AgentToolCatalog
import wasichai.agent.AgentTools
import wasichai.it.support.SliceSmokeTest

class AgentOnlyApiTest : SliceSmokeTest() {
    override val installed = setOf("agent")

    @Autowired
    private lateinit var tools: AgentTools

    @Autowired
    private lateinit var jwtDecoder: ReactiveJwtDecoder

    @Test
    fun `with no key the app boots and the assistant says it is off`() {
        client
            .get()
            .uri("/api/agent/status")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.enabled")
            .isEqualTo(false)
            .jsonPath("$.model")
            .isEqualTo("claude-haiku-4-5")
    }

    @Test
    fun `without workflow a record offers no transitions, and that is not an error`() {
        val created =
            client
                .post()
                .uri("/api/objects/$objectName/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "G-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = created.substringAfter("\"id\":\"").substringBefore("\"")

        // the real service's security hop; no-op without workflow, needed once a workflow-backed port answers
        val jwt = jwtDecoder.decode(admin.removePrefix("Bearer ")).block()!!
        val context = ReactiveSecurityContextHolder.withAuthentication(JwtAuthenticationToken(jwt))
        val result =
            runBlocking(context.asCoroutineContext()) {
                withContext(Dispatchers.IO) {
                    tools.invoke(AgentToolCatalog.AVAILABLE_TRANSITIONS, mapOf("object" to objectName, "id" to id))
                }
            }
        assertThat(result.error).isFalse()
        assertThat(result.json).contains("\"transitions\":[]")
    }
}
