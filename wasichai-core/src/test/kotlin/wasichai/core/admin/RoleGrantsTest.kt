package wasichai.core.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.metadata.FieldPermissionEntry
import wasichai.core.metadata.PermissionEntry

// ADR-085: the one pass behind what the role methods store and what a declared role is compared with
class RoleGrantsTest {
    @Test
    fun `names are trimmed and cased, a blank object is every object, the first of a repeat wins`() {
        val grants =
            RoleGrants.grants(
                listOf(
                    PermissionEntry(" Predio ", " read "),
                    PermissionEntry("predio", "READ", allowed = false),
                    PermissionEntry("  ", "create"),
                    PermissionEntry(null, "CREATE", allowed = false)
                )
            )

        assertThat(grants).containsExactly(PermissionEntry("predio", "READ", true), PermissionEntry(null, "CREATE", true))
    }

    @Test
    fun `a field rule never writes without reading, and the first of a repeat wins`() {
        val rules =
            RoleGrants.rules(
                listOf(
                    FieldPermissionEntry(" Predio ", " Area ", read = false, write = true),
                    FieldPermissionEntry("predio", "area", read = true, write = true)
                )
            )

        assertThat(rules).containsExactly(FieldPermissionEntry("predio", "area", read = false, write = false))
    }

    @Test
    fun `normalising twice is normalising once`() {
        val grants = RoleGrants.grants(listOf(PermissionEntry(" X ", "read")))
        val rules = RoleGrants.rules(listOf(FieldPermissionEntry(" X ", " F ", read = false, write = true)))

        assertThat(RoleGrants.grants(grants)).isEqualTo(grants)
        assertThat(RoleGrants.rules(rules)).isEqualTo(rules)
        assertThat(RoleGrants.name(" caja ")).isEqualTo("CAJA")
    }
}
