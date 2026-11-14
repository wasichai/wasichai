package wasichai.core.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.data.ObjectDefinitionFixtures
import wasichai.core.metadata.AuditMode
import wasichai.core.metadata.FieldType
import wasichai.core.metadata.ObjectDefinition

// issue 164, ADR-079: what a record write stores in the audit log, from the object's mode and its fields' flag
class AuditStatesTest {
    private val codigo = ObjectDefinitionFixtures.field("codigo", FieldType.TEXT)
    private val contenido = ObjectDefinitionFixtures.field("contenido", FieldType.LONG_TEXT).copy(audited = false)

    private fun definition(mode: AuditMode = AuditMode.FULL) = ObjectDefinition(ObjectDefinitionFixtures.obj.copy(auditMode = mode), listOf(codigo, contenido))

    private val before = mapOf("codigo" to "C-1", "contenido" to "uno")
    private val after = mapOf("codigo" to "C-2", "contenido" to "dos")

    @Test
    fun `FULL keeps both states, without the fields left out, which it names`() {
        val entry = AuditStates.of(definition(), before, after)!!

        assertThat(entry.before).isEqualTo(mapOf("codigo" to "C-1", AuditStates.OMITTED to listOf("contenido")))
        assertThat(entry.after).isEqualTo(mapOf("codigo" to "C-2", AuditStates.OMITTED to listOf("contenido")))
        assertThat(AuditStates.omittedIn(entry.after)).containsExactly("contenido")
        assertThat(AuditStates.withoutMarker(entry.after)).isEqualTo(mapOf("codigo" to "C-2"))
    }

    @Test
    fun `a state that does not carry the field names nothing`() {
        val entry = AuditStates.of(definition(), mapOf("codigo" to "C-1"), null)!!

        assertThat(entry.before).isEqualTo(mapOf("codigo" to "C-1"))
        assertThat(entry.after).isNull()
    }

    @Test
    fun `OPERATION keeps the entry without states, NONE keeps no entry`() {
        assertThat(AuditStates.of(definition(AuditMode.OPERATION), before, after)).isEqualTo(AuditStates.Entry(null, null))
        assertThat(AuditStates.of(definition(AuditMode.NONE), before, after)).isNull()
    }

    // a link or a transition carries no field values: a key that happens to match a field name stays
    @Test
    fun `states that are no fields only follow the mode`() {
        val link = mapOf("contenido" to listOf("x"))

        assertThat(AuditStates.of(definition(), link, link, fieldStates = false)).isEqualTo(AuditStates.Entry(link, link))
        assertThat(AuditStates.of(definition(AuditMode.NONE), link, link, fieldStates = false)).isNull()
    }

    @Test
    fun `a mode is parsed in any case, null stays null and anything else is a 400`() {
        assertThat(AuditMode.parse(" operation ")).isEqualTo(AuditMode.OPERATION)
        assertThat(AuditMode.parse(null)).isNull()
        org.assertj.core.api.Assertions
            .assertThatThrownBy { AuditMode.parse("SOMETIMES") }
            .isInstanceOf(wasichai.core.common.ValidationException::class.java)
    }
}
