package wasichai.core.metadata

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Object definitions by organization, by name and by id (ADR-066), and the rest of the model a request reads (ADR-067):
 * a relationship, an object's workflow, automations or rules, keyed by whoever reads them. An entry lives [ttl]; a zero
 * ttl keeps nothing and every lookup loads, as before. Only a definition that was found is kept: a missing object throws
 * before it is. Another value is kept as loaded, null included: no workflow is the usual answer, and a write that adds
 * one drops the organization like any model change.
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

    private class Value(
        val value: Any?,
        val loadedAt: Instant
    )

    private class Bucket {
        val byName = ConcurrentHashMap<String, Entry>()
        val byId = ConcurrentHashMap<UUID, Entry>()
        val values = ConcurrentHashMap<Any, Value>()
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

    // anything else read from the organization's model, under the reader's own key (a private data class, so two
    // modules never share one)
    suspend fun <T> value(
        organizationId: UUID,
        key: Any,
        load: suspend () -> T
    ): T {
        if (!enabled) return load()
        val bucket = bucket(organizationId)
        val now = Instant.now(clock)
        bucket.values[key]?.takeIf { now.isBefore(it.loadedAt.plus(ttl)) }?.let {
            @Suppress("UNCHECKED_CAST")
            return it.value as T
        }
        val loaded = load()
        if (bucket.values.size >= SWEEP_AT) bucket.values.entries.removeIf { !now.isBefore(it.value.loadedAt.plus(ttl)) }
        bucket.values[key] = Value(loaded, now)
        return loaded
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
