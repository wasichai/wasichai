# ADR-064: A field's time zone may be a region in any case or a fixed UTC offset

**Status**: accepted · 2026-10-10 · amends [ADR-063](0063-a-datetime-field-may-name-its-time-zone.md); amends ADR-031
D48 ([ADR-031](0031-deliberate-deviations-from-sapgis.md))

## Context

ADR-063 accepts a `DATETIME` field's `timeZone` only when it is one of `ZoneId.getAvailableZoneIds()`. That set is
case-sensitive and holds region names only, so two reasonable inputs were a `400`
([#99](https://github.com/wasichai/wasichai/issues/99)):

- `america/lima`: the right zone, typed in lowercase.
- `-05:00`: a fixed UTC offset. Some records are defined as "UTC-5" by contract or regulation, with no daylight
  rules, and an app wants exactly that.

`Intl.DateTimeFormat` takes both a region name and a `±HH:MM` offset, so a client can use either form as the API
answers it.

## Decision

**Regions in any case.** A name is matched against `ZoneId.getAvailableZoneIds()` ignoring case (no two tzdb ids
differ only in case) and stored and answered in its canonical spelling: `america/lima` becomes `America/Lima`, `utc`
becomes `UTC`.

**Fixed offsets.** `±HH`, `±HHMM` and `±HH:MM` within `ZoneOffset`'s range (±18:00) are accepted and stored and
answered as `±HH:MM`: `-0500` becomes `-05:00`. Zero is always `+00:00`: `Z`, `-00:00` and `+00` all store it, never
`Z`, so every offset reads in one shape. A region named `UTC` stays `UTC`; it is a region, not an offset.

**Everything else stays a `400` on `timeZone`**: a typo (`America/Limaa`), a prefixed offset (`GMT+5`, `UTC-5`),
seconds in an offset (`+05:00:30`), a one-digit hour (`+5`), an offset out of range (`+19:00`).

**One spelling out.** The response always carries the normalized form, so clients compare and show one text. Fields
stored under ADR-063 already hold canonical region names, so nothing is migrated.

The rest of ADR-063 holds: only `DATETIME` takes a zone, blank is none, the key is written only when set, and nothing
converts.

## Consequences

- An app can pin a field to a fixed offset. That offset never follows daylight saving time, which is the point: pick
  a region when the wall clock should.
- The API normalizes input, so a client that sends `-0500` reads back `-05:00`; it should keep what the API answers.
- Frontend: wasichai/wasichai-ui#54 accepts both forms in the object editor and uses the zone the API answers.
