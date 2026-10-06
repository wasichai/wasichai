package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import java.time.Instant
import java.util.UUID

class NotificationValidationTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val draft = NotificationDraft(NotificationKind.INFO, "Hello", listOf(Audience.All))

    private fun check(
        d: NotificationDraft,
        source: String = "caja",
        allowReserved: Boolean = false
    ) = NotificationValidation.check(source, d, now, allowReserved)

    private fun fields(
        d: NotificationDraft,
        source: String = "caja",
        allowReserved: Boolean = false
    ) = check(d, source, allowReserved).violations.map { it.field }

    @Test
    fun `a plain draft passes`() {
        val result = check(draft)
        assertThat(result.violations).isEmpty()
        assertThat(result.draft!!.title).isEqualTo("Hello")
    }

    @Test
    fun `title is trimmed and 1 to 200 characters`() {
        assertThat(check(draft.copy(title = "  Hi  ")).draft!!.title).isEqualTo("Hi")
        assertThat(fields(draft.copy(title = "   "))).containsExactly("title")
        assertThat(fields(draft.copy(title = "a".repeat(200)))).isEmpty()
        assertThat(fields(draft.copy(title = " " + "a".repeat(200) + " "))).isEmpty()
        assertThat(fields(draft.copy(title = "a".repeat(201)))).containsExactly("title")
    }

    @Test
    fun `body is at most 4000 characters`() {
        assertThat(fields(draft.copy(body = "b".repeat(4000)))).isEmpty()
        assertThat(fields(draft.copy(body = "b".repeat(4001)))).containsExactly("body")
        assertThat(check(draft.copy(body = "")).draft!!.body).isEqualTo("")
    }

    @Test
    fun `source follows its format`() {
        assertThat(fields(draft, source = "caja:pagos-sin_entregar.v2")).isEmpty()
        listOf("C", "caja pagos", "Caja", "1caja", "a", "a".repeat(82)).forEach {
            assertThat(fields(draft, source = it)).describedAs(it).containsExactly("source")
        }
        assertThat(fields(draft, source = "a".repeat(81))).isEmpty()
    }

    @Test
    fun `manual and rule sources are the module's own`() {
        assertThat(fields(draft, source = "manual")).containsExactly("source")
        assertThat(fields(draft, source = "rule:tasa_por_vencer")).containsExactly("source")
        assertThat(fields(draft, source = "manual", allowReserved = true)).isEmpty()
        assertThat(fields(draft, source = "rule:tasa_por_vencer", allowReserved = true)).isEmpty()
        assertThat(fields(draft, source = "manuals")).isEmpty()
    }

    @Test
    fun `key follows its format`() {
        assertThat(fields(draft.copy(key = "7c1e:A/b_c.d-e"))).isEmpty()
        assertThat(fields(draft.copy(key = "a".repeat(200)))).isEmpty()
        assertThat(fields(draft.copy(key = "a".repeat(201)))).containsExactly("key")
        assertThat(fields(draft.copy(key = "with space"))).containsExactly("key")
        assertThat(fields(draft.copy(key = ""))).containsExactly("key")
    }

    @Test
    fun `audience is not empty`() {
        assertThat(fields(draft.copy(audience = emptyList()))).containsExactly("audience")
    }

    @Test
    fun `roles units and emails are normalised`() {
        val id = UUID.randomUUID()
        val result =
            check(
                draft.copy(
                    audience = listOf(Audience.Role(" cajero "), Audience.Unit(" sgft"), Audience.Email("  Ana@Muni.PE "), Audience.User(id), Audience.All)
                )
            )
        assertThat(result.violations).isEmpty()
        assertThat(result.draft!!.audience)
            .containsExactly(Audience.Role("CAJERO"), Audience.Unit("SGFT"), Audience.Email("ana@muni.pe"), Audience.User(id), Audience.All)
    }

    @Test
    fun `a bad role unit or email names its index`() {
        val result =
            check(
                draft.copy(
                    audience = listOf(Audience.All, Audience.Role("caja-chica"), Audience.Unit("S"), Audience.Email("  "), Audience.Role("1A"))
                )
            )
        assertThat(result.violations.map { it.field }).containsExactly("audience[1]", "audience[2]", "audience[3]", "audience[4]")
        assertThat(result.draft).isNull()
    }

    @Test
    fun `route follows its format`() {
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:pagos-sin-entregar")))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Route("my-app:A_b.c-1")))).isEmpty()
        listOf("caja", "Caja:x", "caja:", "caja:a b", ":x", "caja:x/y").forEach {
            assertThat(fields(draft.copy(link = NotificationLink.Route(it)))).describedAs(it).containsExactly("link.route")
        }
    }

    @Test
    fun `a route has at most 10 params`() {
        val ten = (1..10).associate { "p$it" to "v" }
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", ten)))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", ten + ("p11" to "v"))))).containsExactly("link.params")
    }

    @Test
    fun `a route param key and value follow their format`() {
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", mapOf("fecha" to "v".repeat(200)))))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", mapOf("1fecha" to "v"))))).containsExactly("link.params.1fecha")
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", mapOf("a".repeat(41) to "v"))))).hasSize(1)
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", mapOf("fecha" to "v".repeat(201)))))).containsExactly("link.params.fecha")
    }

    @Test
    fun `tab is trimmed and upper-cased`() {
        val id = UUID.randomUUID()
        val record = check(draft.copy(link = NotificationLink.Record("tasa", id, " vigencia "))).draft!!.link
        assertThat(record).isEqualTo(NotificationLink.Record("tasa", id, "VIGENCIA"))
        val route = check(draft.copy(link = NotificationLink.Route("caja:x", tab = "pagos_2"))).draft!!.link
        assertThat(route).isEqualTo(NotificationLink.Route("caja:x", tab = "PAGOS_2"))
    }

    @Test
    fun `a bad tab is refused`() {
        val id = UUID.randomUUID()
        assertThat(fields(draft.copy(link = NotificationLink.Record("tasa", id, "1tab")))).containsExactly("link.tab")
        assertThat(fields(draft.copy(link = NotificationLink.Record("tasa", id, "my-tab")))).containsExactly("link.tab")
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", tab = "T".repeat(41))))).containsExactly("link.tab")
        assertThat(fields(draft.copy(link = NotificationLink.Route("caja:x", tab = "T".repeat(40))))).isEmpty()
    }

    @Test
    fun `a record link needs its object name`() {
        assertThat(fields(draft.copy(link = NotificationLink.Record(" ", UUID.randomUUID())))).containsExactly("link.object")
    }

    @Test
    fun `url is http or https with a host and at most 2000 characters`() {
        assertThat(fields(draft.copy(link = NotificationLink.Url("https://www.munixyz.gob.pe/ordenanzas/2026-006.pdf")))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Url("HTTP://munixyz.gob.pe")))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Url("ftp://munixyz.gob.pe/a")))).containsExactly("link.url")
        assertThat(fields(draft.copy(link = NotificationLink.Url("javascript:alert(1)")))).containsExactly("link.url")
        assertThat(fields(draft.copy(link = NotificationLink.Url("https:///path")))).containsExactly("link.url")
        assertThat(fields(draft.copy(link = NotificationLink.Url("/relative")))).containsExactly("link.url")
        assertThat(fields(draft.copy(link = NotificationLink.Url("https://a b")))).containsExactly("link.url")
        val base = "https://munixyz.gob.pe/"
        assertThat(fields(draft.copy(link = NotificationLink.Url(base + "a".repeat(2000 - base.length))))).isEmpty()
        assertThat(fields(draft.copy(link = NotificationLink.Url(base + "a".repeat(2001 - base.length))))).containsExactly("link.url")
    }

    @Test
    fun `expires after publish`() {
        val publish = now.plusSeconds(3600)
        assertThat(fields(draft.copy(publishAt = publish, expiresAt = publish.plusSeconds(1)))).isEmpty()
        assertThat(fields(draft.copy(publishAt = publish, expiresAt = publish))).containsExactly("expiresAt")
        assertThat(fields(draft.copy(publishAt = publish, expiresAt = publish.minusSeconds(1)))).containsExactly("expiresAt")
    }

    @Test
    fun `without publish expires after now`() {
        assertThat(fields(draft.copy(expiresAt = now.plusSeconds(1)))).isEmpty()
        assertThat(fields(draft.copy(expiresAt = now.minusSeconds(1)))).containsExactly("expiresAt")
        // a past publish may expire before now: it is just ended
        assertThat(fields(draft.copy(publishAt = now.minusSeconds(60), expiresAt = now.minusSeconds(30)))).isEmpty()
    }

    @Test
    fun `times keep microseconds, what the database stores`() {
        val result = check(draft.copy(dueAt = Instant.parse("2026-10-06T12:00:00.123456789Z"))).draft!!
        assertThat(result.dueAt).isEqualTo(Instant.parse("2026-10-06T12:00:00.123456Z"))
    }

    @Test
    fun `every violation is collected`() {
        val result = check(draft.copy(title = "", key = "a b", audience = emptyList()), source = "X")
        assertThat(result.violations.map { it.field }).containsExactlyInAnyOrder("title", "key", "audience", "source")
    }

    @Test
    fun `REST throws a validation exception, Kotlin an illegal argument`() {
        val bad = check(draft.copy(title = ""))
        assertThatThrownBy { bad.orThrow() }
            .isInstanceOf(ValidationException::class.java)
            .hasMessage("Invalid notification")
            .extracting { (it as ValidationException).violations.map(FieldViolation::field) }
            .isEqualTo(listOf("title"))
        assertThatThrownBy { bad.orThrowIllegalArgument() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("title")
        assertThat(check(draft).orThrow().title).isEqualTo("Hello")
    }

    @Test
    fun `prepare sorts and dedupes targets and fingerprints them`() {
        val user = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val unit = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val targets = listOf(StoredTarget.unit(unit), StoredTarget.role("CAJERO"), StoredTarget.user(user), StoredTarget.all(), StoredTarget.user(user))
        val prepared = check(draft.copy(title = " Hello ", key = "k")).orThrow().prepare(targets, null)

        assertThat(prepared.title).isEqualTo("Hello")
        assertThat(prepared.key).isEqualTo("k")
        assertThat(prepared.targets.map { it.type }).containsExactly(TargetType.ALL, TargetType.ROLE, TargetType.UNIT, TargetType.USER)
        assertThat(prepared.fingerprint).hasSize(64)
        assertThat(check(draft.copy(key = "k")).orThrow().prepare(targets.reversed(), null).fingerprint).isEqualTo(prepared.fingerprint)
    }

    @Test
    fun `stored targets keep their shape`() {
        assertThat(StoredTarget.role("CAJERO")).isEqualTo(StoredTarget(TargetType.ROLE, null, "CAJERO", null))
        assertThatThrownBy { StoredTarget(TargetType.USER, null, "CAJERO", null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { StoredTarget(TargetType.ALL, UUID.randomUUID(), null, null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `sources tell the module's own`() {
        assertThat(Sources.rule("tasa_por_vencer")).isEqualTo("rule:tasa_por_vencer")
        assertThat(Sources.isOwnedByModule("manual")).isTrue()
        assertThat(Sources.isOwnedByModule("rule:x")).isTrue()
        assertThat(Sources.isOwnedByModule("caja")).isFalse()
        assertThat(Sources.isManual("manual")).isTrue()
        assertThat(Sources.isManual("rule:x")).isFalse()
        assertThat(Sources.isManual("caja")).isFalse()
    }

    @Test
    fun `Kotlin cuts a long title or body with an ellipsis instead of refusing`() {
        val long = draft.copy(title = "  " + "a".repeat(250) + "  ", body = "b".repeat(5000))

        val cut = NotificationValidation.check("caja", long, now, cutLongText = true).draft!!

        assertThat(cut.title).hasSize(200).endsWith("a…")
        assertThat(cut.body).hasSize(4000).endsWith("b…")
        // within the limit nothing moves
        assertThat(NotificationValidation.check("caja", draft.copy(title = "a".repeat(200)), now, cutLongText = true).draft!!.title).isEqualTo("a".repeat(200))
        // code points, as postgres counts: a cut never splits a surrogate pair
        val emoji = "\uD83D\uDE00".repeat(201)
        val title = NotificationValidation.check("caja", draft.copy(title = emoji), now, cutLongText = true).draft!!.title
        assertThat(title.codePointCount(0, title.length)).isEqualTo(200)
        assertThat(title).endsWith("\uD83D\uDE00…")
        // other rules still hold, and REST still refuses
        assertThat(NotificationValidation.check("caja", long.copy(title = "  "), now, cutLongText = true).violations.map { it.field }).containsExactly("title")
        assertThat(fields(long)).containsExactly("title", "body")
    }
}
