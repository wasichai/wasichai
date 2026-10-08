package wasichai.core.metadata

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import wasichai.core.common.ValidationException
import wasichai.core.identity.AdminAudit
import wasichai.core.identity.CurrentUser
import java.util.UUID

// issue 61: which end a side stands on, and the labels and cardinality each direction reads
class RelationshipSideTest {
    private val org = UUID.randomUUID()
    private val unit = obj("unit", "Unit", "Units")
    private val person = obj("person", "Person", "People")

    private fun obj(
        name: String,
        label: String,
        plural: String
    ) = CustomObject(UUID.randomUUID(), org, name, label, plural, null, true, "${name}__1234abcd", null, null)

    private fun relationship(
        type: RelationshipType,
        source: CustomObject,
        target: CustomObject,
        inverseLabel: String? = "Inverse"
    ) = Relationship(UUID.randomUUID(), org, "rel", "Forward", inverseLabel, type, source.id, target.id, null, null)

    private suspend fun service(): RelationshipService {
        val objects = mock(CustomObjectRepository::class.java)
        listOf(unit, person).forEach { doReturn(it).`when`(objects).findById(org, it.id) }
        return RelationshipService(
            mock(RelationshipRepository::class.java),
            objects,
            mock(CustomFieldRepository::class.java),
            mock(MetadataService::class.java),
            mock(ObjectSchemaManager::class.java),
            mock(CurrentUser::class.java),
            mock(AdminAudit::class.java)
        )
    }

    @Test
    fun `direction parses forward and inverse, null is forward, anything else is a 400 on direction`() {
        assertThat(RelationshipDirection.parse(null)).isEqualTo(RelationshipDirection.FORWARD)
        assertThat(RelationshipDirection.parse(" Forward ")).isEqualTo(RelationshipDirection.FORWARD)
        assertThat(RelationshipDirection.parse("INVERSE")).isEqualTo(RelationshipDirection.INVERSE)
        listOf("", "back", "inverse,forward").forEach {
            val error = assertThrows<ValidationException> { RelationshipDirection.parse(it) }
            assertThat(error.violations.single().field).isEqualTo("direction")
        }
        assertThat(RelationshipDirection.INVERSE.wire).isEqualTo("inverse")
    }

    @Test
    fun `a self-relationship reads forward from its source end and inverse from its target end`() =
        runTest {
            val service = service()
            val expected =
                mapOf(
                    // type to (forward many, inverse many)
                    RelationshipType.MANY_TO_ONE to (false to true),
                    RelationshipType.ONE_TO_MANY to (true to false),
                    RelationshipType.ONE_TO_ONE to (false to false),
                    RelationshipType.MANY_TO_MANY to (true to true)
                )
            expected.forEach { (type, many) ->
                val rel = relationship(type, unit, unit)
                val forward = service.side(rel, unit)
                assertThat(listOf(forward.label, forward.many, forward.direction, forward.fromSource))
                    .describedAs("$type forward")
                    .containsExactly("Forward", many.first, RelationshipDirection.FORWARD, true)
                val inverse = service.side(rel, unit, RelationshipDirection.INVERSE)
                assertThat(listOf(inverse.label, inverse.many, inverse.direction, inverse.fromSource))
                    .describedAs("$type inverse")
                    .containsExactly("Inverse", many.second, RelationshipDirection.INVERSE, false)
                assertThat(inverse.otherObject).isEqualTo(unit)
            }
            // no inverse label: the plural
            assertThat(service.side(relationship(RelationshipType.MANY_TO_ONE, unit, unit, null), unit, RelationshipDirection.INVERSE).label)
                .isEqualTo("Units")
        }

    @Test
    fun `any other relationship keeps its one side per object, with no direction, and refuses inverse`() =
        runTest {
            val service = service()
            val rel = relationship(RelationshipType.MANY_TO_ONE, unit, person)

            val fromUnit = service.side(rel, unit)
            assertThat(listOf(fromUnit.label, fromUnit.many, fromUnit.direction, fromUnit.fromSource, fromUnit.otherObject))
                .containsExactly("Forward", false, null, true, person)
            val fromPerson = service.side(rel, person, RelationshipDirection.FORWARD)
            assertThat(listOf(fromPerson.label, fromPerson.many, fromPerson.direction, fromPerson.fromSource, fromPerson.otherObject))
                .containsExactly("Inverse", true, null, false, unit)

            listOf(unit, person).forEach {
                val error = runCatching { service.side(rel, it, RelationshipDirection.INVERSE) }.exceptionOrNull()
                assertThat(error).isInstanceOf(ValidationException::class.java)
                assertThat((error as ValidationException).violations.single().field).isEqualTo("direction")
            }
        }
}
