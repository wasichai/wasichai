package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

// every replica, every jvm, every release must turn a key into the same lock id, or two replicas
// hold "the same" lock at once. pinned values: changing the hash is a breaking change (ADR-039).
class ClusterLockKeyTest {
    @Test
    fun `a key is the first eight bytes of its sha-256`() {
        assertThat(ClusterLock.lockId("outbox")).isEqualTo(8606968888193004722L)
        assertThat(ClusterLock.lockId("caja.outbox-publisher")).isEqualTo(-8262225705841924839L)
    }

    @Test
    fun `a blank key is refused`() {
        assertThatThrownBy { ClusterLock.lockId(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
