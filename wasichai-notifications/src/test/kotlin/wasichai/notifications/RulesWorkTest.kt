package wasichai.notifications

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import wasichai.core.common.NotFoundException
import wasichai.core.data.RecordStore
import wasichai.core.metadata.CustomField
import wasichai.core.metadata.CustomObject
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// the loop's rules item and a rule's run, without a database
class RulesWorkTest {
    private val org = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val gone = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val there = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    private fun rule(name: String) =
        NotificationRuleDefinition(
            name = name,
            label = name,
            field = "vence",
            stages = listOf(RuleStage(0, NotificationKind.ACTION)),
            untilDays = 3,
            audience = listOf(AudienceJson("ALL", null)),
            title = "Vence"
        )

    private fun stored(
        objectId: UUID,
        name: String
    ) = StoredRule(UUID.randomUUID(), org, objectId, "obj", rule(name))

    private fun definition(
        objectId: UUID,
        type: FieldType = FieldType.DATE
    ) = ObjectDefinition(
        CustomObject(objectId, org, "licencia", "Licencia", "Licencias", null, true, "licencia__abcd1234", null, null),
        listOf(
            CustomField(
                id = UUID.randomUUID(),
                objectId = objectId,
                name = "vence",
                label = "Vence",
                type = type,
                columnName = "vence",
                required = false,
                unique = false,
                defaultValue = null,
                description = null,
                position = 0,
                enumOptions = null,
                relationTargetObjectId = null,
                visible = true,
                editable = true
            )
        )
    )

    @Test
    fun `an object that does not load fails its own rules, not the others`(): Unit =
        runBlocking {
            val ran = mutableListOf<String>()
            val work =
                RulesWork(
                    enabled = { listOf(stored(gone, "a_first"), stored(there, "b_second"), stored(there, "c_third")) },
                    definitionOf = { _, objectId -> if (objectId == gone) throw NotFoundException("Object does not exist") else definition(objectId) },
                    runRule = { _, rule, _, _ ->
                        ran += rule.name
                        // a rule's own timeout is a failure of that rule
                        if (rule.name == "b_second") throw java.util.concurrent.CancellationException("timed out")
                        ReconcileResult(1, 0, 0, 0)
                    },
                    interval = Duration.ofMinutes(15)
                )

            work.run(org, now)

            assertThat(ran).containsExactly("b_second", "c_third")
        }

    @Test
    fun `the object is read once for all its rules`(): Unit =
        runBlocking {
            var reads = 0
            val work =
                RulesWork(
                    enabled = { listOf(stored(there, "a_first"), stored(there, "b_second")) },
                    definitionOf = { _, objectId ->
                        reads++
                        definition(objectId)
                    },
                    runRule = { _, _, _, _ -> ReconcileResult(0, 0, 0, 0) },
                    interval = Duration.ofMinutes(15)
                )

            work.run(org, now)

            assertThat(reads).isEqualTo(1)
        }

    @Test
    fun `a rule whose field is no longer a date is skipped once, before any record is read`(): Unit =
        runBlocking {
            val store = mock(RecordStore::class.java)
            val preparer = mock(NotificationPreparer::class.java)
            val writer = mock(NotificationWriter::class.java)
            val runner = RuleNotifications(store, preparer, writer, ZoneOffset.UTC, "dd/MM/yyyy", 100)

            assertThat(runner.run(org, rule("vence_texto"), definition(there, FieldType.TEXT), now)).isEqualTo(ReconcileResult(0, 0, 0, 0))
            val noField = definition(there).let { it.copy(fields = emptyList()) }
            assertThat(runner.run(org, rule("vence_borrado"), noField, now)).isEqualTo(ReconcileResult(0, 0, 0, 0))

            verifyNoInteractions(store, preparer, writer)
        }
}
