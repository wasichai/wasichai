package wasichai.core.metadata

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import wasichai.core.common.NotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// issue 105 (ADR-066): a definition is loaded once per ttl, never cached when missing, forgotten on a change
class ObjectDefinitionCacheTest {
    private val organizationId = UUID.randomUUID()

    private class MovableClock(
        var now: Instant
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-10-11T12:00:00Z"))

    private fun definition(name: String) =
        ObjectDefinition(
            CustomObject(
                id = UUID.randomUUID(),
                organizationId = organizationId,
                name = name,
                label = name,
                pluralLabel = name,
                description = null,
                enabled = true,
                physicalTable = "${name}__0000",
                createdAt = null,
                updatedAt = null
            ),
            emptyList()
        )

    private class Loads(
        private val definition: ObjectDefinition
    ) {
        var count = 0

        suspend fun load(): ObjectDefinition {
            count++
            return definition
        }
    }

    @Test
    fun `a definition is loaded once within the ttl, by name or by id`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val predio = definition("predio")
            val loads = Loads(predio)

            assertThat(cache.byName(organizationId, "predio", loads::load)).isSameAs(predio)
            assertThat(cache.byName(organizationId, "predio", loads::load)).isSameAs(predio)
            assertThat(cache.byId(organizationId, predio.obj.id, loads::load)).isSameAs(predio)
            assertThat(loads.count).isEqualTo(1)
        }

    @Test
    fun `past the ttl it is loaded again`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val loads = Loads(definition("predio"))

            cache.byName(organizationId, "predio", loads::load)
            clock.now = clock.now.plusSeconds(5)
            cache.byName(organizationId, "predio", loads::load)

            assertThat(loads.count).isEqualTo(2)
        }

    @Test
    fun `a zero ttl caches nothing`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ZERO, clock)
            val loads = Loads(definition("predio"))

            repeat(3) { cache.byName(organizationId, "predio", loads::load) }

            assertThat(cache.enabled).isFalse()
            assertThat(loads.count).isEqualTo(3)
        }

    @Test
    fun `a missing object is never cached`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            var asked = 0
            val missing: suspend () -> ObjectDefinition = {
                asked++
                throw NotFoundException("Object 'predio' does not exist")
            }

            repeat(2) {
                val failed = runCatching { cache.byName(organizationId, "predio", missing) }
                assertThat(failed.exceptionOrNull()).isInstanceOf(NotFoundException::class.java)
            }

            assertThat(asked).isEqualTo(2)
        }

    @Test
    fun `evicting an organization forgets its definitions, and only its own`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val other = UUID.randomUUID()
            val mine = Loads(definition("predio"))
            val theirs = Loads(definition("predio"))

            cache.byName(organizationId, "predio", mine::load)
            cache.byName(other, "predio", theirs::load)
            cache.evict(organizationId)
            cache.byName(organizationId, "predio", mine::load)
            cache.byName(other, "predio", theirs::load)

            assertThat(mine.count).isEqualTo(2)
            assertThat(theirs.count).isEqualTo(1)
        }

    @Test
    fun `a load that read the model before a change is not kept after it`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val stale = definition("predio")
            val fresh = Loads(definition("predio"))

            // the change lands while the load is on its way back from the database
            cache.byName(organizationId, "predio") {
                cache.evict(organizationId)
                stale
            }

            assertThat(cache.byName(organizationId, "predio", fresh::load)).isNotSameAs(stale)
            assertThat(fresh.count).isEqualTo(1)
        }

    // issue 108 (ADR-067): the rest of the model a request reads, under its reader's key

    private data class WorkflowOf(
        val objectId: UUID
    )

    private class Values<T>(
        private val value: T
    ) {
        var count = 0

        suspend fun load(): T {
            count++
            return value
        }
    }

    @Test
    fun `a value is loaded once within the ttl, per key, null included`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val object1 = UUID.randomUUID()
            val none = Values<String?>(null)
            val other = Values<String?>("otro")

            repeat(2) { cache.value(organizationId, WorkflowOf(object1), none::load) }
            repeat(2) { cache.value(organizationId, WorkflowOf(UUID.randomUUID()), other::load) }

            assertThat(cache.value(organizationId, WorkflowOf(object1), none::load)).isNull()
            assertThat(none.count).isEqualTo(1)
            assertThat(other.count).isEqualTo(2)
        }

    @Test
    fun `past the ttl, or once the organization is evicted, a value is loaded again`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ofSeconds(5), clock)
            val key = WorkflowOf(UUID.randomUUID())
            val loads = Values("flujo")

            cache.value(organizationId, key, loads::load)
            clock.now = clock.now.plusSeconds(5)
            cache.value(organizationId, key, loads::load)
            cache.evict(organizationId)
            cache.value(organizationId, key, loads::load)

            assertThat(loads.count).isEqualTo(3)
        }

    @Test
    fun `a zero ttl keeps no value`() =
        runTest {
            val cache = ObjectDefinitionCache(Duration.ZERO, clock)
            val loads = Values("flujo")

            repeat(2) { cache.value(organizationId, WorkflowOf(UUID.randomUUID()), loads::load) }

            assertThat(loads.count).isEqualTo(2)
        }
}
