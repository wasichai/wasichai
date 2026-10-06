package wasichai.it.full

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.notifications.NotificationLoop
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// /api/objects/{object}/notification-rules (spec C, date rules). the app's clock decides "today": a clock of our own,
// at 22:00 in Lima while it is already tomorrow in UTC, so a day counted in the wrong zone shows.
// the loop is off (tick 0s); the cap is 3 so one test can overflow it.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(NotificationRuleApiTest.ClockConfig::class)
@TestPropertySource(
    properties = [
        "wasichai.notifications.tick=0s",
        "wasichai.notifications.zone=America/Lima",
        "wasichai.notifications.rule-max-notifications=3"
    ]
)
class NotificationRuleApiTest : FullAppIntegrationTest() {
    // fixed unless a test moves it
    class TestClock(
        @Volatile var now: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

        override fun instant(): Instant = now
    }

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun testClock(): TestClock = TestClock(NOW)
    }

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var clock: TestClock

    @Autowired
    private lateinit var loop: NotificationLoop

    private val tenant by lazy { NotificationsTenant.provision(client, db, schemas) }
    private val role by lazy { tenant.createRole() }
    private val json = JsonMapper.builder().build()

    private val fields = mapOf("codigo" to "TEXT", "vence" to "DATE", "vence_a" to "DATETIME", "estado" to "TEXT", "cuotas" to "INTEGER")

    // today in Lima is 2026-10-06; window of the default rule: [10-03, 10-21]
    private fun rule(
        name: String = unique(),
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> =
        mapOf(
            "name" to name,
            "label" to "  Licencias por vencer  ",
            "field" to "vence",
            "stages" to listOf(mapOf("fromDays" to 0, "kind" to "ACTION"), mapOf("fromDays" to -15, "kind" to "WARNING")),
            "untilDays" to 3,
            "conditions" to listOf(mapOf("field" to "estado", "op" to "EQ", "value" to "VIGENTE")),
            "audience" to listOf(mapOf("type" to "ROLE", "value" to role.lowercase())),
            "title" to "La licencia {{codigo}} vence el {{date}}",
            "body" to "Quedan {{days}} días.",
            "tab" to "vigencia"
        ) + extra

    @Test
    fun `a rule is created, read, listed, replaced and deleted`() {
        val obj = tenant.createObject(fields)
        val other = tenant.createObject(fields)
        val name = unique()

        val created = call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        assertThat(created.get("object").asString()).isEqualTo(obj)
        assertThat(created.get("name").asString()).isEqualTo(name)
        assertThat(created.get("label").asString()).isEqualTo("Licencias por vencer")
        assertThat(created.get("enabled").asBoolean()).isTrue()
        assertThat(created.get("tab").asString()).isEqualTo("VIGENCIA")
        assertThat(created.get("stages").toList().map { it.get("fromDays").asInt() }).containsExactly(-15, 0)
        assertThat(created.get("audience").toList().map { it.get("value").asString() }).containsExactly(role)

        assertThat(call("GET", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.OK)).isEqualTo(created)
        assertThat(names(call("GET", "/api/objects/$obj/notification-rules", null, HttpStatus.OK))).containsExactly(name)

        val elsewhere = unique()
        call("POST", "/api/objects/$other/notification-rules", rule(elsewhere), HttpStatus.CREATED)
        val all = call("GET", "/api/notification-rules", null, HttpStatus.OK).toList().associate { it.get("name").asString() to it.get("object").asString() }
        assertThat(all).containsEntry(name, obj).containsEntry(elsewhere, other)
        // another object's rule is not this object's
        call("GET", "/api/objects/$obj/notification-rules/$elsewhere", null, HttpStatus.NOT_FOUND)

        val replaced = call("PUT", "/api/objects/$obj/notification-rules/$name", rule(name, mapOf("label" to "Otra", "body" to null)), HttpStatus.OK)
        assertThat(replaced.get("label").asString()).isEqualTo("Otra")
        assertThat(replaced.get("body").isNull).isTrue()
        // the path names the rule: a body that names another one is refused
        assertThat(errorFields(call("PUT", "/api/objects/$obj/notification-rules/$name", rule(unique()), HttpStatus.BAD_REQUEST))).containsExactly("name")
        call("PUT", "/api/objects/$obj/notification-rules/no_such_rule", rule("no_such_rule"), HttpStatus.NOT_FOUND)

        call("DELETE", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.NO_CONTENT)
        call("GET", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.NOT_FOUND)
        call("GET", "/api/objects/no_such_object/notification-rules", null, HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a bad rule is a 400 naming its field, a taken name a 409`() {
        val obj = tenant.createObject(fields)

        fun refused(extra: Map<String, Any?>): List<String> =
            errorFields(call("POST", "/api/objects/$obj/notification-rules", rule(extra = extra), HttpStatus.BAD_REQUEST))

        assertThat(refused(mapOf("field" to "codigo"))).containsExactly("field")
        assertThat(refused(mapOf("conditions" to listOf(mapOf("field" to "nope", "op" to "EMPTY"))))).containsExactly("conditions[0].field")
        assertThat(refused(mapOf("conditions" to listOf(mapOf("field" to "cuotas", "op" to "EQ", "value" to "tres"))))).containsExactly("conditions[0].value")
        assertThat(refused(mapOf("title" to "Vence {{nope}}"))).containsExactly("title")
        assertThat(
            refused(mapOf("audience" to listOf(mapOf("type" to "ALL"), mapOf("type" to "ROLE", "value" to "NO_SUCH_ROLE"))))
        ).containsExactly("audience[1]")
        assertThat(refused(mapOf("tab" to "1-bad"))).containsExactly("tab")
        // an op the enum does not know never reaches validation: the json codec refuses it
        call(
            "POST",
            "/api/objects/$obj/notification-rules",
            rule(extra = mapOf("conditions" to listOf(mapOf("field" to "estado", "op" to "GT")))),
            HttpStatus.BAD_REQUEST
        )

        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CONFLICT)
        // names are unique in the organization, not per object
        call("POST", "/api/objects/${tenant.createObject(fields)}/notification-rules", rule(name), HttpStatus.CONFLICT)
    }

    @Test
    fun `without MANAGE_METADATA a rule is a 403`() {
        val obj = tenant.createObject(fields)
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        val reader = tenant.createUser(roles = listOf(tenant.createRole(permissions = listOf(NotificationsTenant.Grant("READ")))))

        call("GET", "/api/notification-rules", null, HttpStatus.FORBIDDEN, reader.token)
        call("GET", "/api/objects/$obj/notification-rules", null, HttpStatus.FORBIDDEN, reader.token)
        call("GET", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.FORBIDDEN, reader.token)
        call("POST", "/api/objects/$obj/notification-rules", rule(), HttpStatus.FORBIDDEN, reader.token)
        call("POST", "/api/objects/$obj/notification-rules/$name/run", null, HttpStatus.FORBIDDEN, reader.token)
        call("DELETE", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.FORBIDDEN, reader.token)
    }

    @Test
    fun `creating a rule runs it - one notification per record in the window, in the rule's zone`() {
        val obj = tenant.createObject(fields)
        val warning = record(obj, "A-1", "2026-10-10")
        val action = record(obj, "A-2", "2026-10-05")
        // 3 days ago in Lima, 4 in UTC: the last day of the window only in the rule's zone
        val edge = record(obj, "A-3", "2026-10-03")
        record(obj, "OUT-1", "2026-11-30")
        record(obj, "OUT-2", "2026-10-02")
        record(obj, "ANULADA", "2026-10-08", estado = "ANULADA")
        val name = unique()

        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)

        val open = open(name)
        assertThat(open.keys).containsExactlyInAnyOrder(warning, action, edge)
        val a1 = open.getValue(warning)
        assertThat(a1.get("kind").asString()).isEqualTo("WARNING")
        assertThat(a1.get("title").asString()).isEqualTo("La licencia A-1 vence el 10/10/2026")
        assertThat(a1.get("body").asString()).isEqualTo("Quedan 4 días.")
        assertThat(a1.get("source").asString()).isEqualTo("rule:$name")
        assertThat(a1.get("link").get("type").asString()).isEqualTo("RECORD")
        assertThat(a1.get("link").get("object").asString()).isEqualTo(obj)
        assertThat(a1.get("link").get("recordId").asString()).isEqualTo(warning.toString())
        assertThat(a1.get("link").get("tab").asString()).isEqualTo("VIGENCIA")
        assertThat(a1.get("audience").toList().map { it.get("value").asString() }).containsExactly(role)
        // a DATE is due at the start of the next day in Lima
        assertThat(Instant.parse(a1.get("dueAt").asString())).isEqualTo(Instant.parse("2026-10-11T05:00:00Z"))
        assertThat(open.getValue(action).get("kind").asString()).isEqualTo("ACTION")
        assertThat(open.getValue(edge).get("kind").asString()).isEqualTo("ACTION")
        assertThat(open.getValue(edge).get("body").asString()).isEqualTo("Quedan -3 días.")
    }

    @Test
    fun `run answers what it changed, and a later day escalates the stage`() {
        val obj = tenant.createObject(fields)
        val id = record(obj, "E-1", "2026-10-10")
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)

        assertThat(run(obj, name)).isEqualTo(Counts(0, 0, 0, 0))
        val notification = open(name).getValue(id).get("id").asString()
        sql("UPDATE ${schemas.metadata}.notifications SET resolved_at = now() WHERE id = CAST(:id AS uuid)", notification)
        assertThat(run(obj, name)).isEqualTo(Counts(0, 0, 1, 0))

        try {
            clock.now = NOW.plus(Duration.ofDays(4))
            assertThat(run(obj, name)).isEqualTo(Counts(0, 1, 0, 0))
            assertThat(open(name).getValue(id).get("kind").asString()).isEqualTo("ACTION")
            clock.now = NOW.plus(Duration.ofDays(8))
            assertThat(run(obj, name)).isEqualTo(Counts(0, 0, 0, 1))
            assertThat(open(name)).isEmpty()
        } finally {
            clock.now = NOW
        }
    }

    @Test
    fun `the loop's rules item runs every enabled rule`() {
        val obj = tenant.createObject(fields)
        val id = record(obj, "R-1", "2026-10-10")
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        sql("UPDATE ${schemas.metadata}.notifications SET resolved_at = now() WHERE id = CAST(:id AS uuid)", open(name).getValue(id).get("id").asString())
        assertThat(open(name)).isEmpty()

        assertThat(runBlocking { loop.runSource("rules") }).isTrue()

        assertThat(open(name).keys).containsExactly(id)
    }

    @Test
    fun `record writes keep the notification in step, without a run`() {
        val obj = tenant.createObject(fields)
        val leaving = record(obj, "L-1", "2026-10-10")
        val arriving = record(obj, "L-2", "2026-12-31")
        val deleted = record(obj, "L-3", "2026-10-12")
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        assertThat(open(name).keys).containsExactlyInAnyOrder(leaving, deleted)

        // renewed: out of the window
        update(obj, leaving, "L-1", "2027-10-10")
        update(obj, arriving, "L-2", "2026-10-07")
        assertThat(open(name).keys).containsExactlyInAnyOrder(arriving, deleted)
        assertThat(open(name).getValue(arriving).get("title").asString()).isEqualTo("La licencia L-2 vence el 07/10/2026")

        // a condition that stops holding resolves too
        update(obj, arriving, "L-2", "2026-10-07", estado = "ANULADA")
        assertThat(open(name).keys).containsExactly(deleted)

        call("DELETE", "/api/objects/$obj/records/$deleted", null, HttpStatus.NO_CONTENT)
        assertThat(open(name)).isEmpty()
        // a new record in the window is published at once
        val created = record(obj, "L-4", "2026-10-15")
        assertThat(open(name).keys).containsExactly(created)
    }

    @Test
    fun `disabling or deleting a rule resolves what it published`() {
        val obj = tenant.createObject(fields)
        val first = record(obj, "D-1", "2026-10-10")
        val second = record(obj, "D-2", "2026-10-11")
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        assertThat(open(name).keys).containsExactlyInAnyOrder(first, second)

        call("PUT", "/api/objects/$obj/notification-rules/$name", rule(name, mapOf("enabled" to false)), HttpStatus.OK)
        assertThat(open(name)).isEmpty()
        // disabled: a write publishes nothing and run is refused
        update(obj, first, "D-1", "2026-10-09")
        assertThat(open(name)).isEmpty()
        call("POST", "/api/objects/$obj/notification-rules/$name/run", null, HttpStatus.CONFLICT)

        call("PUT", "/api/objects/$obj/notification-rules/$name", rule(name), HttpStatus.OK)
        assertThat(open(name).keys).containsExactlyInAnyOrder(first, second)

        call("DELETE", "/api/objects/$obj/notification-rules/$name", null, HttpStatus.NO_CONTENT)
        assertThat(open(name)).isEmpty()
    }

    @Test
    fun `deleting the object resolves what its rules published`() {
        val obj = tenant.createObject(fields)
        record(obj, "O-1", "2026-10-10")
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)
        assertThat(open(name)).hasSize(1)

        call("DELETE", "/api/objects/$obj", null, HttpStatus.NO_CONTENT)

        assertThat(open(name)).isEmpty()
    }

    @Test
    fun `a rule notifies the earliest records up to the cap`() {
        val obj = tenant.createObject(fields)
        val earliest = listOf("2026-10-04", "2026-10-06", "2026-10-09").mapIndexed { i, date -> record(obj, "C-$i", date) }
        record(obj, "C-late", "2026-10-20")
        val name = unique()

        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)

        assertThat(open(name).keys).containsExactlyInAnyOrderElementsOf(earliest)
    }

    @Test
    fun `a DATETIME rule is due at the value`() {
        val obj = tenant.createObject(fields)
        val id = tenant.createRecord(obj, mapOf("codigo" to "T-1", "vence_a" to "2026-10-08T15:30:00Z", "estado" to "VIGENTE"))
        val name = unique()

        call("POST", "/api/objects/$obj/notification-rules", rule(name, mapOf("field" to "vence_a", "conditions" to emptyList<Any>())), HttpStatus.CREATED)

        val notification = open(name).getValue(id)
        assertThat(Instant.parse(notification.get("dueAt").asString())).isEqualTo(Instant.parse("2026-10-08T15:30:00Z"))
        assertThat(notification.get("title").asString()).isEqualTo("La licencia T-1 vence el 08/10/2026")
    }

    @Test
    fun `a field a rule reads cannot be deleted`() {
        val obj = tenant.createObject(fields)
        val name = unique()
        call("POST", "/api/objects/$obj/notification-rules", rule(name), HttpStatus.CREATED)

        listOf("vence", "estado", "codigo").forEach { field ->
            val problem = call("DELETE", "/api/metadata/objects/$obj/fields/$field", null, HttpStatus.CONFLICT)
            assertThat(problem.get("detail").asString()).contains("notification rule '$name'")
        }
        call("DELETE", "/api/metadata/objects/$obj/fields/cuotas", null, HttpStatus.NO_CONTENT)
    }

    private data class Counts(
        val created: Int,
        val updated: Int,
        val reopened: Int,
        val resolved: Int
    )

    private fun run(
        obj: String,
        name: String
    ): Counts {
        val result = call("POST", "/api/objects/$obj/notification-rules/$name/run", null, HttpStatus.OK)
        assertThat(result.propertyNames().asSequence().toList()).containsExactlyInAnyOrder("created", "updated", "reopened", "resolved")
        return Counts(result.get("created").asInt(), result.get("updated").asInt(), result.get("reopened").asInt(), result.get("resolved").asInt())
    }

    // the rule's open notifications by record id, through the admin api
    private fun open(name: String): Map<UUID, JsonNode> =
        call("GET", "/api/notifications?source=rule:$name&status=open&size=100", null, HttpStatus.OK)
            .get("content")
            .toList()
            .associateBy { UUID.fromString(it.get("key").asString()) }

    private fun record(
        obj: String,
        codigo: String,
        vence: String,
        estado: String = "VIGENTE"
    ): UUID = tenant.createRecord(obj, mapOf("codigo" to codigo, "vence" to vence, "estado" to estado))

    private fun update(
        obj: String,
        id: UUID,
        codigo: String,
        vence: String,
        estado: String = "VIGENTE"
    ) {
        call("PUT", "/api/objects/$obj/records/$id", mapOf("attributes" to mapOf("codigo" to codigo, "vence" to vence, "estado" to estado)), HttpStatus.OK)
    }

    private fun names(list: JsonNode): List<String> = list.toList().map { it.get("name").asString() }

    private fun errorFields(problem: JsonNode): List<String> = problem.get("errors").toList().map { it.get("field").asString() }

    private fun sql(
        statement: String,
        id: String
    ) {
        db
            .sql(statement)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .block()
    }

    private fun call(
        method: String,
        uri: String,
        body: Any?,
        expected: HttpStatus,
        token: String = tenant.admin
    ): JsonNode {
        val request = client.method(HttpMethod.valueOf(method)).uri(uri).header(HttpHeaders.AUTHORIZATION, token)
        val result = (if (body == null) request else request.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
        if (result.status.value() != expected.value()) {
            throw AssertionError("$method $uri answered ${result.status}, expected $expected\nresponse body: ${result.responseBody}")
        }
        return json.readTree(result.responseBody ?: "null")
    }

    private fun unique(): String =
        "r_" +
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

    private companion object {
        // 22:00 on 2026-10-06 in Lima; already 2026-10-07 in UTC
        val NOW: Instant = Instant.parse("2026-10-07T03:00:00Z")
    }
}
