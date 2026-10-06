package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

class NotificationLinkJsonTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val id = UUID.fromString("7c1e0000-0000-0000-0000-000000000001")

    private fun tree(value: Any) = mapper.readTree(mapper.writeValueAsString(value))

    @Test
    fun `a record link names its object`() {
        val json = tree(NotificationLink.Record("tasa", id, "VIGENCIA").toJson())
        assertThat(json).isEqualTo(mapper.readTree("""{"type":"RECORD","object":"tasa","recordId":"$id","tab":"VIGENCIA"}"""))
    }

    @Test
    fun `null fields are left out`() {
        assertThat(tree(NotificationLink.Record("tasa", id).toJson())).isEqualTo(mapper.readTree("""{"type":"RECORD","object":"tasa","recordId":"$id"}"""))
        assertThat(tree(NotificationLink.Url("https://a.pe/x.pdf").toJson())).isEqualTo(mapper.readTree("""{"type":"URL","url":"https://a.pe/x.pdf"}"""))
    }

    @Test
    fun `a route carries its params`() {
        val json = tree(NotificationLink.Route("caja:pagos-sin-entregar", mapOf("fecha" to "2026-10-06")).toJson())
        assertThat(json).isEqualTo(mapper.readTree("""{"type":"ROUTE","route":"caja:pagos-sin-entregar","params":{"fecha":"2026-10-06"}}"""))
    }

    @Test
    fun `the spec's wire shapes read back`() {
        val record = mapper.readValue<LinkJson>("""{"type": "RECORD", "object": "tasa", "recordId": "$id", "tab": "VIGENCIA"}""")
        assertThat(record.toLink()).isEqualTo(NotificationLink.Record("tasa", id, "VIGENCIA"))
        val route = mapper.readValue<LinkJson>("""{"type": "ROUTE", "route": "caja:pagos-sin-entregar", "params": {"fecha": "2026-10-06"}, "tab": null}""")
        assertThat(route.toLink()).isEqualTo(NotificationLink.Route("caja:pagos-sin-entregar", mapOf("fecha" to "2026-10-06")))
        val url = mapper.readValue<LinkJson>("""{"type": "URL", "url": "https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf"}""")
        assertThat(url.toLink()).isEqualTo(NotificationLink.Url("https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf"))
    }

    @Test
    fun `every link round trips`() {
        listOf(
            NotificationLink.Record("tasa", id, "VIGENCIA"),
            NotificationLink.Record("tasa", id),
            NotificationLink.Route("caja:x", mapOf("a" to "1"), "PAGOS"),
            NotificationLink.Route("caja:x"),
            NotificationLink.Url("https://a.pe")
        ).forEach { link ->
            val back = mapper.readValue<LinkJson>(mapper.writeValueAsString(link.toJson())).toLink()
            assertThat(back).isEqualTo(link)
        }
    }

    @Test
    fun `a bad type or a missing part is refused`() {
        fun violations(json: LinkJson) =
            runCatching { json.toLink() }.exceptionOrNull().let { (it as ValidationException).violations.map(FieldViolation::field) }

        assertThat(violations(LinkJson("MAIL", url = "x"))).containsExactly("link.type")
        assertThat(violations(LinkJson("RECORD", recordId = id))).containsExactly("link.object")
        assertThat(violations(LinkJson("RECORD", objectName = "tasa"))).containsExactly("link.recordId")
        assertThat(violations(LinkJson("ROUTE"))).containsExactly("link.route")
        assertThat(violations(LinkJson("URL"))).containsExactly("link.url")
    }

    @Test
    fun `audience reads its wire shapes`() {
        val user = UUID.randomUUID()
        val json =
            mapper.readValue<List<AudienceJson>>(
                """[{"type":"ALL"},{"type":"USER","value":"$user"},{"type":"EMAIL","value":"a@b.pe"},{"type":"ROLE","value":"CAJERO"},{"type":"UNIT","value":"SGFT"}]"""
            )
        val audience = json.toAudiences()
        assertThat(audience).containsExactly(Audience.All, Audience.User(user), Audience.Email("a@b.pe"), Audience.Role("CAJERO"), Audience.Unit("SGFT"))
        assertThat(tree(Audience.All.toJson())).isEqualTo(mapper.readTree("""{"type":"ALL"}"""))
        assertThat(audience.map { it.toJson() }).isEqualTo(json)
    }

    @Test
    fun `a bad audience names its index`() {
        assertThatThrownBy { listOf(AudienceJson("ALL"), AudienceJson("USER", "nope"), AudienceJson("ROLE"), AudienceJson("TEAM", "x")).toAudiences() }
            .isInstanceOf(ValidationException::class.java)
            .extracting { (it as ValidationException).violations.map(FieldViolation::field) }
            .isEqualTo(listOf("audience[1]", "audience[2]", "audience[3]"))
    }

    @Test
    fun `the summary always has every kind`() {
        val latest = LatestNotification(id, NotificationKind.ACTION, "Pagar", Instant.parse("2026-10-06T12:00:00Z"))
        val summary = NotificationSummary.of(mapOf(NotificationKind.ACTION to KindCount(2, 1, 1)), latest)
        val expected =
            """
            {"kinds":{"INFO":{"active":0,"unread":0,"overdue":0},"WARNING":{"active":0,"unread":0,"overdue":0},
             "ACTION":{"active":2,"unread":1,"overdue":1}},
             "latest":{"id":"$id","kind":"ACTION","title":"Pagar","publishAt":"2026-10-06T12:00:00Z"}}
            """.trimIndent()
        assertThat(tree(summary)).isEqualTo(mapper.readTree(expected))
        assertThat(tree(NotificationSummary.of(emptyMap(), null)).get("latest").isNull).isTrue()
        assertThat(
            tree(NotificationSummary.of(emptyMap(), null))
                .get("kinds")
                .propertyNames()
                .asSequence()
                .toList()
        ).containsExactly("INFO", "WARNING", "ACTION")
    }

    @Test
    fun `an inbox item writes every key`() {
        val item =
            InboxItem(
                id = id,
                kind = NotificationKind.INFO,
                title = "Hola",
                body = null,
                link = null,
                publishAt = Instant.parse("2026-10-06T12:00:00Z"),
                expiresAt = null,
                dueAt = null,
                overdue = false,
                source = "manual",
                read = false,
                snoozedUntil = null,
                dismissible = true
            )
        assertThat(tree(item).propertyNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id",
            "kind",
            "title",
            "body",
            "link",
            "publishAt",
            "expiresAt",
            "dueAt",
            "overdue",
            "source",
            "read",
            "snoozedUntil",
            "dismissible"
        )
    }
}
