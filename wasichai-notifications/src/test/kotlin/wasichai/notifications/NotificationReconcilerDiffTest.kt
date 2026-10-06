package wasichai.notifications

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class NotificationReconcilerDiffTest {
    private val resolvedAt = Instant.parse("2026-10-01T00:00:00Z")

    // when the stored rows were last written, unless a test says otherwise
    private val written = Instant.parse("2026-09-30T00:00:00Z")

    private fun draft(
        key: String?,
        fingerprint: String = "fp-$key",
        kind: NotificationKind = NotificationKind.ACTION
    ) = PreparedNotification(
        kind = kind,
        title = "t",
        body = null,
        link = null,
        linkObjectId = null,
        targets = listOf(StoredTarget.all()),
        key = key,
        publishAt = null,
        expiresAt = null,
        dueAt = null,
        fingerprint = fingerprint
    )

    private fun stored(
        key: String?,
        fingerprint: String = "fp-$key",
        resolvedAt: Instant? = null,
        updatedAt: Instant = written
    ) = StoredRow(UUID.randomUUID(), key, NotificationKind.ACTION, fingerprint, resolvedAt, publishAt = written, updatedAt = updatedAt)

    @Test
    fun `a key nobody stored is created`() {
        val plan = NotificationReconciler.diff(emptyList(), listOf(draft("a")))

        assertThat(plan.steps).containsExactly(ReconcileStep(ReconcileAction.CREATE, draft("a"), null))
        assertThat(plan.resolve).isEmpty()
        assertThat(plan.changes).isTrue()
    }

    @Test
    fun `an open row with the same fingerprint is skipped`() {
        val row = stored("a")
        val plan = NotificationReconciler.diff(listOf(row), listOf(draft("a")))

        assertThat(plan.steps).containsExactly(ReconcileStep(ReconcileAction.SKIP, draft("a"), row))
        assertThat(plan.resolve).isEmpty()
        assertThat(plan.changes).isFalse()
    }

    @Test
    fun `an open row with another fingerprint is updated`() {
        val row = stored("a", fingerprint = "old")
        val plan = NotificationReconciler.diff(listOf(row), listOf(draft("a")))

        assertThat(plan.steps).containsExactly(ReconcileStep(ReconcileAction.UPDATE, draft("a"), row))
    }

    @Test
    fun `a resolved row comes back, even unchanged`() {
        val row = stored("a", resolvedAt = resolvedAt)
        val plan = NotificationReconciler.diff(listOf(row), listOf(draft("a")))

        assertThat(plan.steps).containsExactly(ReconcileStep(ReconcileAction.REOPEN, draft("a"), row))
    }

    @Test
    fun `open rows missing from the drafts are resolved, a keyless one too`() {
        val kept = stored("a")
        val missing = stored("b")
        val keyless = stored(null)
        val alreadyResolved = stored("c", resolvedAt = resolvedAt)

        val plan = NotificationReconciler.diff(listOf(kept, missing, keyless, alreadyResolved), listOf(draft("a")))

        assertThat(plan.resolve).containsExactlyInAnyOrder(missing, keyless)
        assertThat(plan.changes).isTrue()
    }

    @Test
    fun `no drafts resolve every open row`() {
        val a = stored("a")
        val plan = NotificationReconciler.diff(listOf(a), emptyList())

        assertThat(plan.steps).isEmpty()
        assertThat(plan.resolve).containsExactly(a)
    }

    @Test
    fun `a row written after the read began is neither updated, reopened nor resolved`() {
        val readStart = Instant.parse("2026-10-02T00:00:00Z")
        val later = readStart.plusMillis(1)
        val updated = stored("a", fingerprint = "listener", updatedAt = later)
        val resolvedByListener = stored("b", resolvedAt = later, updatedAt = later)
        val unreported = stored("c", updatedAt = later)
        val older = stored("d", fingerprint = "old")
        // a frozen clock stamps the read's own instant: not later, so not left alone
        val sameInstant = stored("e", updatedAt = readStart)

        val plan =
            NotificationReconciler.diff(
                listOf(updated, resolvedByListener, unreported, older, sameInstant),
                listOf(draft("a"), draft("b"), draft("d")),
                readStart
            )

        assertThat(plan.steps.map { it.action }).containsExactly(ReconcileAction.SKIP, ReconcileAction.SKIP, ReconcileAction.UPDATE)
        assertThat(plan.resolve).containsExactly(sameInstant)
        // no readStart, no guard
        assertThat(NotificationReconciler.diff(listOf(unreported), emptyList()).resolve).containsExactly(unreported)
    }

    @Test
    fun `a draft without a key refuses the batch`() {
        assertThatThrownBy { NotificationReconciler.diff(emptyList(), listOf(draft("a"), draft(null))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("key")
    }

    @Test
    fun `two drafts with one key refuse the batch`() {
        assertThatThrownBy { NotificationReconciler.diff(emptyList(), listOf(draft("a"), draft("b"), draft("a", fingerprint = "x"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'a'")
    }
}
