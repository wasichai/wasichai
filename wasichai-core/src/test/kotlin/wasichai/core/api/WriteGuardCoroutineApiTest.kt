package wasichai.core.api

import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ForbiddenException
import wasichai.core.data.RecordRequest
import wasichai.core.data.RecordService
import wasichai.core.data.RecordWrite
import wasichai.core.data.RecordWriteGuard
import wasichai.core.data.RelatedRecordService
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

// ADR-040: a guard runs in the caller's coroutine. An app marks its own in-process writes with a context element and its guard
// refuses the unmarked ones: the generic record api, here. Modelled on caja-backend (issue #15).
@Import(WriteGuardCoroutineApiTest.GuardConfig::class)
class WriteGuardCoroutineApiTest : WasichaiIntegrationTest() {
    class AppMarker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<AppMarker>
    }

    // refuses a write to a "marked" object unless the caller's coroutine carries the marker
    class MarkerGuard : RecordWriteGuard {
        override suspend fun beforeWrite(change: RecordWrite) {
            if (change.objectName.startsWith("marked") && coroutineContext[AppMarker] == null) {
                throw ForbiddenException("Only the application writes '${change.objectName}'")
            }
        }
    }

    // app code: its own endpoints write in-process, inside the marker
    @RestController
    class AppEndpoint(
        private val records: RecordService,
        private val related: RelatedRecordService
    ) {
        @PostMapping("/app/{objectName}")
        suspend fun create(
            @PathVariable objectName: String
        ): String = withContext(AppMarker()) { records.create(objectName, RecordRequest(mapOf("codigo" to "BY-THE-APP"))).id }

        @PutMapping("/app/{objectName}/{id}")
        suspend fun update(
            @PathVariable objectName: String,
            @PathVariable id: UUID
        ) {
            withContext(AppMarker()) { records.update(objectName, id, RecordRequest(mapOf("codigo" to "CHANGED-BY-THE-APP"))) }
        }

        @DeleteMapping("/app/{objectName}/{id}")
        suspend fun delete(
            @PathVariable objectName: String,
            @PathVariable id: UUID
        ) {
            withContext(AppMarker()) { records.delete(objectName, id) }
        }

        @PostMapping("/app/{objectName}/{id}/link/{relationship}/{otherId}")
        suspend fun link(
            @PathVariable objectName: String,
            @PathVariable id: UUID,
            @PathVariable relationship: String,
            @PathVariable otherId: UUID,
            @RequestParam(defaultValue = "true") marked: Boolean
        ) {
            if (marked) {
                withContext(AppMarker()) { related.link(objectName, id, relationship, otherId) }
            } else {
                related.link(objectName, id, relationship, otherId)
            }
        }
    }

    @TestConfiguration
    class GuardConfig {
        @Bean
        fun markerGuard(): RecordWriteGuard = MarkerGuard()

        @Bean
        fun appEndpoint(
            records: RecordService,
            related: RelatedRecordService
        ) = AppEndpoint(records, related)
    }

    private lateinit var admin: String

    @BeforeEach
    fun setUp() {
        admin = bearer()
    }

    @Test
    fun `the guard sees the caller's marker on in-process writes and refuses the REST api`() {
        val name = uniqueName("marked")
        createObject(name)

        // the generic api carries no marker: refused, nothing stored
        client
            .post()
            .uri("/api/objects/$name/records")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "BY-REST")))
            .exchange()
            .expectStatus()
            .isForbidden
        assertThat(codigos(name)).isEmpty()

        // the app's own create is marked
        val id = app(HttpStatus.OK, client.post().uri("/app/$name")).expectBody(String::class.java).returnResult().responseBody!!
        assertThat(codigos(name)).containsExactly("BY-THE-APP")

        // update and delete: REST refused and unchanged, app marked
        client
            .put()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "BY-REST")))
            .exchange()
            .expectStatus()
            .isForbidden
        client
            .delete()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isForbidden
        assertThat(codigos(name)).containsExactly("BY-THE-APP")
        app(HttpStatus.OK, client.put().uri("/app/$name/$id"))
        assertThat(codigos(name)).containsExactly("CHANGED-BY-THE-APP")
        app(HttpStatus.OK, client.delete().uri("/app/$name/$id"))
        assertThat(codigos(name)).isEmpty()
    }

    @Test
    fun `a related-record link sees the marker too`() {
        val a = uniqueName("marked")
        val b = uniqueName("marked")
        createObject(a)
        createObject(b)
        val relationship = uniqueName("rel").take(30)
        client
            .post()
            .uri("/api/relationships")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to relationship, "label" to "Links", "type" to "MANY_TO_MANY", "source" to a, "target" to b))
            .exchange()
            .expectStatus()
            .isCreated
        val ida = app(HttpStatus.OK, client.post().uri("/app/$a")).expectBody(String::class.java).returnResult().responseBody!!
        val idb = app(HttpStatus.OK, client.post().uri("/app/$b")).expectBody(String::class.java).returnResult().responseBody!!

        // REST and an unmarked in-process call are both refused
        client
            .post()
            .uri("/api/objects/$a/records/$ida/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("otherId" to idb))
            .exchange()
            .expectStatus()
            .isForbidden
        app(HttpStatus.FORBIDDEN, client.post().uri("/app/$a/$ida/link/$relationship/$idb?marked=false"))
        assertThat(linked(a, ida, relationship)).isEqualTo(0)

        app(HttpStatus.OK, client.post().uri("/app/$a/$ida/link/$relationship/$idb"))
        assertThat(linked(a, ida, relationship)).isEqualTo(1)
    }

    private fun app(
        status: HttpStatus,
        spec: WebTestClient.RequestHeadersSpec<*>
    ): WebTestClient.ResponseSpec =
        spec
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .also { it.expectStatus().isEqualTo(status) }

    private fun createObject(name: String) {
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(mapOf("name" to name, "label" to name, "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
    }

    private fun codigos(name: String): List<String> {
        val page =
            client
                .get()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(Map::class.java)
                .returnResult()
                .responseBody!!
        return (page["content"] as List<*>).map { ((it as Map<*, *>)["attributes"] as Map<*, *>)["codigo"] as String }
    }

    private fun linked(
        name: String,
        id: String,
        relationship: String
    ): Int =
        client
            .get()
            .uri("/api/objects/$name/records/$id/related/$relationship")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!["totalElements"] as Int
}
