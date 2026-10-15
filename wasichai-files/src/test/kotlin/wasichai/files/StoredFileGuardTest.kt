package wasichai.files

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.common.ValidationException
import wasichai.core.data.PlatformRun
import wasichai.core.data.RecordChangeKind
import wasichai.core.data.RecordWrite
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.util.UUID

class StoredFileGuardTest {
    private val organization = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val objectId = UUID.randomUUID()
    private val mine = UUID.randomUUID()
    private val held = UUID.randomUUID()
    private val run = UUID.randomUUID()
    private val staged = UUID.randomUUID()

    // the object has one file field, "acta"; the user uploaded `mine` for it, the platform run `run` staged `staged`
    private inner class Files : StoredFileRepository(mock(DatabaseClient::class.java), WasichaiSchemas("wasichai", "app_data")) {
        val asked = mutableListOf<UUID>()
        var fieldLookups = 0

        override suspend fun fileFieldNames(objectId: UUID): Set<String> {
            fieldLookups++
            return if (objectId == this@StoredFileGuardTest.objectId) setOf("acta") else emptySet()
        }

        override suspend fun uploadedBy(
            organizationId: UUID,
            userId: UUID,
            objectId: UUID,
            fieldName: String,
            ids: Collection<UUID>,
            maxAge: Duration
        ): Set<UUID> {
            asked += ids
            val own = organizationId == organization && userId == user && objectId == this@StoredFileGuardTest.objectId && fieldName == "acta"
            return if (own) ids.filter { it == mine }.toSet() else emptySet()
        }

        override suspend fun stagedBy(
            organizationId: UUID,
            run: UUID,
            objectId: UUID,
            fieldName: String,
            ids: Collection<UUID>,
            maxAge: Duration
        ): Set<UUID> {
            asked += ids
            val own =
                organizationId == organization && run == this@StoredFileGuardTest.run && objectId == this@StoredFileGuardTest.objectId && fieldName == "acta"
            return if (own) ids.filter { it == staged }.toSet() else emptySet()
        }
    }

    private val files = Files()
    private var current: PlatformRun? = null
    private val guard = StoredFileGuard(files, Duration.ofHours(12)) { current }

    @Test
    fun `the writer's own upload for the field may be attached, on create and on update`(): Unit =
        runBlocking {
            guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to mine.toString())))
            guard.beforeWrite(write(RecordChangeKind.UPDATED, before = mapOf("acta" to descriptor(held)), attributes = mapOf("acta" to mine)))
            assertThat(files.asked).containsExactly(mine, mine)
        }

    @Test
    fun `what the field holds passes unchanged and null clears, with no lookup`(): Unit =
        runBlocking {
            val before = mapOf("acta" to descriptor(held), "codigo" to "A")
            guard.beforeWrite(write(RecordChangeKind.UPDATED, before = before, attributes = before))
            guard.beforeWrite(write(RecordChangeKind.UPDATED, before = before, attributes = mapOf("acta" to held.toString())))
            guard.beforeWrite(write(RecordChangeKind.UPDATED, before = before, attributes = mapOf("acta" to null)))
            assertThat(files.asked).isEmpty()
        }

    @Test
    fun `anyone else's file, or any file for the platform, is a 400 on the field`() {
        val stranger = UUID.randomUUID()
        refused { guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to stranger.toString()))) }
        refused { guard.beforeWrite(write(RecordChangeKind.UPDATED, before = mapOf("acta" to null), attributes = mapOf("acta" to descriptor(stranger)))) }
        refused { guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to mine), userId = null)) }
        refused { guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to "not-a-uuid"))) }
    }

    // ADR-068: the platform's writer is its run
    @Test
    fun `the platform attaches what its own run staged`(): Unit =
        runBlocking {
            current = PlatformRun(organization, run)
            guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to staged.toString()), userId = null))
            guard.beforeWrite(write(RecordChangeKind.UPDATED, before = mapOf("acta" to descriptor(held)), attributes = mapOf("acta" to staged), userId = null))
            assertThat(files.asked).containsExactly(staged, staged)
        }

    @Test
    fun `another run, another organization, a person's upload or no run at all is a 400 on the field`() {
        val write = write(RecordChangeKind.CREATED, attributes = mapOf("acta" to staged), userId = null)
        // another block of the same organization
        current = PlatformRun(organization, UUID.randomUUID())
        refused { guard.beforeWrite(write) }
        // a run of another organization never reaches this one's files, not even asked
        current = PlatformRun(UUID.randomUUID(), run)
        files.asked.clear()
        refused { guard.beforeWrite(write) }
        assertThat(files.asked).isEmpty()
        // the right run, a person's upload
        current = PlatformRun(organization, run)
        refused { guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to mine), userId = null)) }
        // no user and no run: an automation
        current = null
        refused { guard.beforeWrite(write) }
    }

    @Test
    fun `a person never attaches what the platform staged, even inside its run`() {
        current = PlatformRun(organization, run)
        refused { guard.beforeWrite(write(RecordChangeKind.CREATED, attributes = mapOf("acta" to staged))) }
    }

    @Test
    fun `objects without file fields, deletes and transitions cost one lookup or none`(): Unit =
        runBlocking {
            guard.beforeWrite(write(RecordChangeKind.UPDATED, attributes = mapOf("acta" to UUID.randomUUID()), objectId = UUID.randomUUID()))
            assertThat(files.fieldLookups).isEqualTo(1)
            guard.beforeWrite(write(RecordChangeKind.DELETED, before = mapOf("acta" to descriptor(held))))
            guard.beforeWrite(write(RecordChangeKind.TRANSITIONED, before = mapOf("acta" to descriptor(held))))
            guard.beforeWrite(write(RecordChangeKind.UPDATED, attributes = emptyMap()))
            assertThat(files.fieldLookups).isEqualTo(1)
        }

    private fun refused(block: suspend () -> Unit) {
        assertThatThrownBy { runBlocking { block() } }.isInstanceOfSatisfying(ValidationException::class.java) { e ->
            assertThat(e.violations.single().field).isEqualTo("acta")
        }
    }

    private fun descriptor(id: UUID) = FileDescriptor(id, "acta.pdf", "application/pdf", 10, "a".repeat(64)).toMap()

    private fun write(
        kind: RecordChangeKind,
        before: Map<String, Any?>? = null,
        attributes: Map<String, Any?>? = null,
        userId: UUID? = user,
        objectId: UUID = this.objectId
    ) = RecordWrite(
        organizationId = organization,
        userId = userId,
        objectId = objectId,
        objectName = "expediente",
        recordId = if (kind == RecordChangeKind.CREATED) null else UUID.randomUUID(),
        kind = kind,
        before = before,
        attributes = attributes
    )
}
