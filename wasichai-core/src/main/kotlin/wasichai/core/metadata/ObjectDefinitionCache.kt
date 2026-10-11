package wasichai.core.metadata

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Object definitions by organization, by name and by id (ADR-066). An entry lives [ttl]; a zero ttl keeps nothing and
 * every lookup loads, as before. Only a definition that was found is kept: a missing object throws before it is.
 *
 * A change drops the whole organization ([evict]): changes to the model are rare, and a relationship touches more
 * than one object. The organization's bucket is replaced, not emptied, so a load that read the old model before the
 * change lands in the bucket nobody reads any more.
 */
class ObjectDefinitionCache(
    private val ttl: Duration,
    private val clock: Clock = Clock.systemUTC()
) {
    private class Entry(
        val definition: ObjectDefinition,
        val loadedAt: Instant
    )

    private class Bucket {
        val byName = ConcurrentHashMap<String, Entry>()
        val byId = ConcurrentHashMap<UUID, Entry>()
    }

    private val buckets = ConcurrentHashMap<UUID, Bucket>()

    val enabled: Boolean get() = !ttl.isZero

    suspend fun byName(
        organizationId: UUID,
        name: String,
        load: suspend () -> ObjectDefinition
    ): ObjectDefinition {
        if (!enabled) return load()
        val bucket = bucket(organizationId)
        fresh(bucket.byName[name])?.let { return it }
        return load().also { remember(bucket, it) }
    }

    suspend fun byId(
        organizationId: UUID,
        id: UUID,
        load: suspend () -> ObjectDefinition
    ): ObjectDefinition {
        if (!enabled) return load()
        val bucket = bucket(organizationId)
        fresh(bucket.byId[id])?.let { return it }
        return load().also { remember(bucket, it) }
    }

    // the organization's model changed, or is about to: forget all of it
    fun evict(organizationId: UUID) {
        buckets.remove(organizationId)
    }

    private fun bucket(organizationId: UUID): Bucket = buckets.computeIfAbsent(organizationId) { Bucket() }

    private fun fresh(entry: Entry?): ObjectDefinition? = entry?.takeIf { Instant.now(clock).isBefore(it.loadedAt.plus(ttl)) }?.definition

    // both keys: the next lookup by either one hits. expired entries swept once a bucket grows, so it stays about as
    // big as the objects read within one ttl
    private fun remember(
        bucket: Bucket,
        definition: ObjectDefinition
    ) {
        val now = Instant.now(clock)
        if (bucket.byId.size >= SWEEP_AT) {
            bucket.byName.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(ttl)) }
            bucket.byId.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(ttl)) }
        }
        val entry = Entry(definition, now)
        bucket.byName[definition.obj.name] = entry
        bucket.byId[definition.obj.id] = entry
    }

    private companion object {
        const val SWEEP_AT = 1_000
    }
}
