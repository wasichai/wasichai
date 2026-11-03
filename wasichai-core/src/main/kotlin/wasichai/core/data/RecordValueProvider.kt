package wasichai.core.data

import wasichai.core.identity.AuthenticatedUser
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectDefinition

/**
 * SPI: values the server fixes on a record being created, such as its owner being whoever calls (ADR-077). Asked
 * on every create, before the `RecordWriteGuard`s and the field validation: a guard sees the final attributes, and
 * a required field can be covered this way. Part of the create itself: one row, one audit entry, the answer carries
 * the value, and `CREATE` is enough. Never asked on an update.
 *
 * A key returned beats what the caller sent and the field's default, and lands even on a field the caller, or
 * anyone (`editable: false`), may not write: it is the server's value, not the caller's input. `null` is a value:
 * the field holds nothing, or a required one is the usual `400`. A key left out changes nothing. Values as the api
 * carries them (a UUID or a date as its string), validated like any sent value.
 *
 * A key that is no attribute of the object, a system column or a section field among them, fails the write with an
 * `IllegalStateException`: a bug of the app, and the caller's `500`. So does a key two beans return, whatever their
 * values: which one owns a field never depends on `@Order`.
 *
 * Asked as [RecordWriteGuard] is: in the caller's coroutine and transaction. In `createAll`, once per record, in
 * order, before the first insert, so a read does not see the batch's earlier records. Collected from every bean.
 */
interface RecordValueProvider {
    /**
     * What to fix on a new record of [definition]; empty for an object it has no say on.
     *
     * [attributes]: what the caller sent, with no default and no other bean's value in it, so the provider can tell
     * a sent value: ignore it by returning its own, refuse it by throwing a `ValidationException` (`400`, nothing
     * stored), or keep it by leaving the key out. A field the caller's roles may not write never gets here sent: that
     * is refused first.
     *
     * [caller]: the person or service account who writes, `ADMIN` included. null: nobody, the platform (`asPlatform`,
     * ADR-039) or an automation's `CREATE_RECORD` (ADR-016). The organization is `definition.obj.organizationId`.
     */
    suspend fun valuesFor(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        caller: AuthenticatedUser?
    ): Map<String, Any?>
}

/**
 * Every [RecordValueProvider], asked by whoever creates a record: `RecordService` and automations. One bean, not
 * replaceable: an app adds a provider, it never removes another one (as RecordWriteGuards, ADR-040). With no
 * provider, a create is what it was.
 */
class RecordValueProviders(
    private val providers: List<RecordValueProvider>
) {
    /** The values to write over [attributes], every key checked. Land them with [writing]. */
    suspend fun fixedFor(
        definition: ObjectDefinition,
        attributes: Map<String, Any?>,
        caller: AuthenticatedUser?,
        types: FieldTypeRegistry
    ): Map<String, Any?> {
        val fixed = linkedMapOf<String, Any?>()
        val owners = hashMapOf<String, RecordValueProvider>()
        providers.forEach { provider ->
            provider.valuesFor(definition, attributes, caller).forEach { (name, value) ->
                // the store skips an unknown key without a word, the guards would still see it. say so instead
                val field = definition.fields.firstOrNull { it.name == name }
                check(field != null && types.handler(field.type).section == null) {
                    "${provider.javaClass.name} fixes '$name', which is no attribute of '${definition.obj.name}'"
                }
                val other = owners.put(name, provider)
                check(other == null) { "${other?.javaClass?.name} and ${provider.javaClass.name} both fix '$name' of '${definition.obj.name}'" }
                fixed[name] = value
            }
        }
        return fixed
    }

    companion object {
        val NONE = RecordValueProviders(emptyList())
    }
}

/** The definition with [names] writable, as [wasichai.core.metadata.FieldDefaults] does for a default: the store then writes them. */
fun ObjectDefinition.writing(names: Set<String>): ObjectDefinition {
    if (names.isEmpty()) return this
    return copy(fields = fields.map { if (it.name in names) it.copy(editable = true) else it })
}
