package wasichai.core.data

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.audit.AuditDiff
import wasichai.core.audit.FieldChange
import java.util.UUID

// a link is an UPDATE of what the record is related to, shown as one change keyed by the relationship
class LinkChangeTest {
    private val other = UUID.randomUUID()

    @Test
    fun `linking reads as the other record appearing`() {
        val (before, after) = linkChange("predio_titulares", other, linked = true)
        assertThat(AuditDiff.changes(before, after)).containsExactly(FieldChange("rel:predio_titulares", null, other.toString()))
    }

    @Test
    fun `unlinking reads as it going away`() {
        val (before, after) = linkChange("predio_titulares", other, linked = false)
        assertThat(AuditDiff.changes(before, after)).containsExactly(FieldChange("rel:predio_titulares", other.toString(), null))
    }
}
