package wasichai.core.identity.oidc

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SameRolesTest {
    // code points list SRTMX before SRTM_LECTOR ("X" < "_"); a collation that ignores "_" the other way. neither is a difference
    @Test
    fun `an order is no difference`() {
        assertThat(sameRoles(listOf("SRTMX", "SRTM_LECTOR"), listOf("SRTM_LECTOR", "SRTMX"))).isTrue()
        assertThat(sameRoles(listOf("SRTMX", "SRTM_LECTOR"), listOf("SRTMX", "SRTM_LECTOR").sorted())).isTrue()
    }

    @Test
    fun `another set is a difference`() {
        assertThat(sameRoles(listOf("A"), listOf("A", "B"))).isFalse()
        assertThat(sameRoles(emptyList(), listOf("A"))).isFalse()
        assertThat(sameRoles(emptyList(), emptyList())).isTrue()
    }
}
