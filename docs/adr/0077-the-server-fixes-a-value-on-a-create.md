# ADR-077: The server fixes a value on a create, through a `RecordValueProvider`

**Status**: accepted · 2026-11-03 · builds on [ADR-040](0040-append-only-objects-and-a-pre-write-guard.md) and
[ADR-062](0062-batch-record-creation.md)

## Context

An app needs fields the server fills when a record is created, from who calls. `curso.propietario_id` in
`wasichai/elearning-backend` is the record's owner: a UUID, not required, off the forms, and whoever creates the course,
since no form takes initial values and no screen shows a user's id
([#153](https://github.com/wasichai/wasichai/issues/153)). Nothing does it today:

- `RecordWriteGuard` can veto, not set: `RecordWrite.attributes` is read only and `RecordService` never reads it back;
- `RecordChangeListener` runs after the write: not atomic, a second audit row, `UPDATE` on top of `CREATE`, and the
  `POST`'s answer lacks the value;
- `defaultValue` is fixed text, so it cannot be the caller;
- `created_by` is filled by the server, but it is a system column, not a field of the model.

## Decision

### The SPI

```kotlin
interface RecordValueProvider {
    suspend fun valuesFor(definition: ObjectDefinition, attributes: Map<String, Any?>, caller: AuthenticatedUser?): Map<String, Any?>
}
```

Every bean is collected by `RecordValueProviders` (core, one bean, not replaceable). With no bean a create is what it
was. It has one job: values on a create. An update never asks it.

### Where it runs

Inside the create, after the checks of the caller and before anything else looks at the values:

1. the gate: permission (`CREATE`, nothing more), disabled object, api-only door;
2. what the caller sent against their field rules: a field they may not write is still a `400`, and the provider is not
   asked;
3. **the providers**;
4. a required field nobody may write (`403`), unless a provider fixed it or it has a default;
5. the defaults of what is still left out;
6. appendOnly, requiresReason, the `RELATION` check and the `RecordWriteGuard`s, on the final attributes;
7. the store, which validates each value through its field type, so a required field a provider covers passes;
8. one audit row and the listeners, with the row as stored; the answer, to what the caller may read.

So it is one row and one audit entry, and the `POST`'s answer has the value. A caller who may not read the field does not
get it in the answer, as for any field.

### What a key means

A key returned **beats what the caller sent and the field's default**, and lands even on a field the caller, or anyone
(`editable: false`), may not write. As with a default, it is the server's value, not the caller's input, so
`writing(names)` makes those fields writable for this insert only. `null` is a value: the field holds nothing, no default
fills it, and on a required field it is the usual `400`. A key left out changes nothing. Values are as the API carries
them, validated by the field type like any sent value; a `RELATION` value is held to the reader's scope like a sent one.

### What the caller sent

`attributes` is what the caller sent: no default and no other bean's value in it. Otherwise a provider could not tell a
sent value from a default. So the implementer decides:

- **ignore it**: return its own key, as `propietario_id` does;
- **refuse it**: throw a `ValidationException` from the provider (`400`, nothing stored);
- **keep it**: leave the key out. A guard, which sees the final attributes, can still refuse it, for example with a `400`
  when it is not the caller's id.

`editable: false` covers what comes after the create: a `PUT` leaves the field alone and a `PATCH` naming it is a `403`,
while the provider still writes it on the create.

### A key that is not a field

Today the store takes only the definition's attribute fields: an unknown key is skipped without a word, though guards
would see it. From a caller that is harmless and stays as it is. From a provider it is a bug that would otherwise pass in
silence, and with `writing` it must never reach anything but a real field. So a key that is **no attribute of the
object** fails the write with `IllegalStateException` (the caller's `500`, nothing stored, no guard asked): a misspelt
field, a system column (`created_by`, `organization_id`, `id`, none of them a field, and `SystemColumns` keeps fields from
taking those names) or a field that travels in a section (those are not written through `attributes`).

### Two beans on one field

A key two beans return fails the write the same way, even with equal values. `@Order` picking a winner would make who owns
a field depend on bean ordering, in silence, for something that decides who owns a record. Refusing can be fixed at once
and shows on the first write. Each bean sees only what the caller sent, so beans fixing different fields do not depend on
each other, and they all land.

### Batch and background

| Path | Provider asked | `caller` |
|---|---|---|
| `POST /api/objects/{o}/records` | yes (a replayed `Idempotency-Key` asks nothing) | the person or service account, `ADMIN` included |
| `RecordService.create` as a user | yes | the user |
| `RecordService.createAll` | yes, once per record, in order, before the first insert | as above |
| `asPlatform`, `forEachOrganization` | yes | null |
| Automation `CREATE_RECORD` | yes | null, also when a person triggered the run |
| Any update, link, transition, automation `UPDATE_FIELD` | no | |

With no user in context the provider is still asked, with `caller = null`, and answers what it wishes: nothing, a fixed
value or a refusal. The organization is `definition.obj.organizationId`. `RecordService.create` with no user and no
platform throws `401` before reaching a provider, as it does today.

A provider runs in the caller's coroutine and transaction, as a guard does. In `createAll` it runs in the up-front step of
ADR-062: an exception is kept and thrown when the loop reaches that record, so the first failing record wins and nothing is
stored, but a read inside a provider does not see the batch's earlier records. The batch's `RELATION` lookups include what
providers fixed.

An automation writes through `RecordStore`, not `RecordService`, so `AutomationRunner` asks `RecordValueProviders` itself.
A provider's refusal or bad key fails the run, as a guard's veto does. **Saving a rule is unchanged:**
`AutomationService` still asks a `CREATE_RECORD` rule to name a required field with no default. What a provider will answer
depends on the write, so it cannot be known then: the rule names a value and the provider's wins.

### Why not

- **A guard that returns attributes.** A guard judges, and its contract is that it changes nothing; every write path calls
  guards, and only a create fixes values.
- **A `defaultValue` expression such as `${user.id}`.** A small language in metadata for one case, which still could not
  read anything else.
- **An `appliesTo` as in `RecordReadMask`, or a name filter on the bean.** There a read pays a query for it; here returning
  an empty map costs nothing.

The SPI has one consumer so far, the app that asked for it. Rule 10's second user is the collector's: `RecordService` and
`AutomationRunner`. Core cannot do this any other way, since only the app knows its fields.

**No ADR-031 entry.** With no provider, every create, query and answer is what it was. The REST contract gains nothing.

## Consequences

- An app fixes an owner with one bean, and `CREATE` is all the caller needs.
- A new path that creates a record must ask `RecordValueProviders`, next to `RecordWriteGuards` (ADR-040's checklist). Code
  that writes through `RecordStore` on its own and does not ask gets no values, as it gets no defaults.
- A field a provider fixes on a create is still open to a later write unless it is `editable: false`, a field rule or a
  guard says otherwise. That is the app's to declare, since only it knows the field.
- `RecordService` and `AutomationRunner` take a defaulted `RecordValueProviders`, so code that builds them by hand keeps
  compiling. `RecordService`'s private `creation` step is now `suspend`, and "judged without the database" no longer holds
  for it when a provider reads.
- Tested by `RecordServiceValueProviderTest`, `AutomationRunnerValueProviderTest`, the integration test
  `RecordValueProviderApiTest` (the `propietario_id` case end to end) and `AutomationWriteRulesTest`.
