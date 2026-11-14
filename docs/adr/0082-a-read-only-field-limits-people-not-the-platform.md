# ADR-082: A read-only field limits people, not the platform

**Status**: accepted · 2026-11-14 · refines
[ADR-039](0039-background-work-runs-as-the-platform-with-a-cluster-lock.md) and
[ADR-051](0051-optimistic-locking-and-partial-update-of-records.md)

## Context

`PhysicalTableRecordStore` builds its `INSERT` and `UPDATE` from the fields whose `editable` is true. A field with
`editable: false` was dropped without a word, also when the platform wrote it
([#166](https://github.com/wasichai/wasichai/issues/166)): `create` answered the field `null`, `update` left it, and
nothing said why. `hneyra/curricula` marked its catalog fields read-only so nobody edits them in the UI, publishes them
with `RecordService.asPlatform`, and found its records empty.

`editable` already meant two things: the field's metadata (the UI draws it read-only, a `PATCH` naming it is a `403`,
ADR-051) and, inside the services, a field the caller's roles may not write (`writableBy`) or that a `PATCH` did not
send (`lockedBut`). The store reads the second; the first leaked into it.

## Decision

**`editable: false` limits people, not the platform.** Inside `asPlatform`, a create, a `PUT` and a `PATCH` write
every read-only field they send. A read-only field a platform `PUT` leaves out keeps its stored value: only what it
names is its to write. A field computed by the server can then be read-only in the UI without making the whole object
`apiOnly` (ADR-040).

**A person's value is refused, not dropped.** A person's create (the REST `POST`, or an in-process create with a
token) that sends a non-`null` value for a read-only field is a `403` "Field '…' is read-only", as a `PATCH` already
was. A key a `RecordValueProvider` fixes is the server's and passes (ADR-077); a `null` is no value. A person's `PUT`
still ignores read-only fields: it is a full replace, and a client that saves what it read would otherwise fail on
every field it never touched (ADR-051).

**In-process values as the column stores them.** The codec takes a `java.util.UUID` for a `UUID` or `RELATION` field,
a `LocalDate` for a `DATE`, and an `OffsetDateTime`, a `ZonedDateTime` or an `Instant` (UTC) for a `DATETIME`, beside
their text, as `DECIMAL` takes a `Number` ([#167](https://github.com/wasichai/wasichai/issues/167)). A `LocalDateTime`
names no instant, so it stays refused. What a read answers is unchanged.

Automations keep their rule: an `UPDATE_FIELD` action cannot name a read-only field, which is checked when the rule is
saved.

## Consequences

- An app writes its server-computed fields as the platform without opening the object to the generic API.
- Breaking, for 2.0.0: a platform write that sent a read-only field now stores it, and a person's create that sent one
  is a `403` where the value was dropped. ADR-031 D55.
- Tested by `PlatformFieldWriteApiTest`, `FieldValueCodecTest` and `RecordServiceValueProviderTest`.
