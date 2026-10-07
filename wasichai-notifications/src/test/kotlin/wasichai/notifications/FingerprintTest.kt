package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private val user = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private val unit = UUID.fromString("00000000-0000-0000-0000-00000000000b")
private val record = UUID.fromString("00000000-0000-0000-0000-00000000000c")
private val at = Instant.parse("2026-10-06T12:00:00Z")

class FingerprintTest {
    private data class Input(
        val kind: NotificationKind = NotificationKind.WARNING,
        val title: String = "La tasa vence",
        val body: String? = "Quedan 3 días.",
        val link: NotificationLink? = NotificationLink.Record("tasa", record, "VIGENCIA"),
        val publishAt: Instant? = null,
        val expiresAt: Instant? = null,
        val dueAt: Instant? = null,
        val targets: List<StoredTarget> = emptyList()
    )

    private val base = Input(targets = listOf(StoredTarget.user(user), StoredTarget.role("CAJERO"), StoredTarget.unit(unit)))

    private fun hash(i: Input) = fingerprint(i.kind, i.title, i.body, i.link, i.publishAt, i.expiresAt, i.dueAt, i.targets)

    @Test
    fun `equal content gives equal hash`() {
        assertThat(hash(base)).isEqualTo(hash(base.copy())).matches("^[0-9a-f]{64}$")
    }

    @Test
    fun `target order does not matter`() {
        assertThat(hash(base.copy(targets = base.targets.reversed()))).isEqualTo(hash(base))
    }

    @Test
    fun `any change changes the hash`() {
        val variants =
            listOf(
                base.copy(kind = NotificationKind.ACTION),
                base.copy(title = "La tasa vencio"),
                base.copy(body = "Quedan 2 días."),
                base.copy(body = null),
                base.copy(link = null),
                base.copy(link = NotificationLink.Record("tasa", record)),
                base.copy(link = NotificationLink.Record("tasas", record, "VIGENCIA")),
                base.copy(link = NotificationLink.Route("caja:x")),
                base.copy(link = NotificationLink.Route("caja:x", mapOf("a" to "1"))),
                base.copy(link = NotificationLink.Url("https://a.pe")),
                base.copy(publishAt = at),
                base.copy(expiresAt = at),
                base.copy(dueAt = at),
                base.copy(targets = base.targets.drop(1)),
                base.copy(targets = base.targets + StoredTarget.all())
            )
        val hashes = variants.map { hash(it) }
        assertThat(hashes).doesNotContain(hash(base))
        assertThat(hashes).doesNotHaveDuplicates()
    }

    @Test
    fun `null body and empty body differ`() {
        assertThat(hash(base.copy(body = null))).isNotEqualTo(hash(base.copy(body = "")))
    }

    @Test
    fun `fields do not bleed into each other`() {
        assertThat(hash(base.copy(title = "ab", body = "c"))).isNotEqualTo(hash(base.copy(title = "a", body = "bc")))
        val ab = NotificationLink.Route("caja:x", mapOf("a" to "b", "c" to "d"))
        val ba = NotificationLink.Route("caja:x", mapOf("c" to "d", "a" to "b"))
        assertThat(hash(base.copy(link = ab))).isEqualTo(hash(base.copy(link = ba)))
    }

    @Test
    fun `a draft without publish keeps its hash`() {
        val first = hash(base)
        assertThat(hash(base)).isEqualTo(first)
        assertThat(hash(base.copy(publishAt = at))).isNotEqualTo(first)
    }
}
