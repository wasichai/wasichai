# ADR-063: A DATETIME field may name its time zone

**Status**: accepted · 2026-10-10 · adds ADR-031 D48 ([ADR-031](0031-deliberate-deviations-from-sapgis.md))

## Context

A `DATETIME` field stores an instant (`timestamptz`), but nothing in its metadata says whose wall clock it belongs to.
The frontend had to guess: first the browser's zone, then an app-wide `config.timeZone`
([wasichai/wasichai-ui#46](https://github.com/wasichai/wasichai-ui/issues/46)). App-wide is not enough when one
object mixes zones: an incident that "occurred at" the site's time next to a deadline in the head office's, or a
project with sites in several countries. The zone is a property of the field, so every client (web, agents,
automations, exports) should read the same answer from the field's metadata
([#91](https://github.com/wasichai/wasichai/issues/91)).

## Decision

**`timeZone` on the field.** `FieldRequest`, `UpdateFieldRequest` and `FieldResponse` gain an optional `timeZone`, an
IANA region name (`America/Lima`), stored in `custom_fields.time_zone` (core `V18`, nullable text) and carried in the
field's audit snapshot.

**Only on `DATETIME`, only a region.** A zone on any other type is a `400` naming `timeZone`, and so is a name that is
not one of `ZoneId.getAvailableZoneIds()`. That set is case-sensitive and holds region names (plus `UTC` and the
`Etc/` aliases), so a fixed offset such as `+05:00` or `GMT+5` is refused: an offset has no daylight rules and is not
a place's zone. The check lives in the service (`FieldTimeZones`); PostgreSQL has no immutable way to ask whether a
zone name exists, so there is no `CHECK`.

**Blank is none.** Left out or blank on create, the field has none. On update, left out keeps it, blank clears it and
a name replaces it, the same rule as `defaultValue`.

**Only written when set**, like `indexed` (ADR-036): a field without a zone has no `timeZone` key, so a model that
declares none reads exactly as before. `timeZone` joins the core field keys, so a module field type cannot claim it.

**Nothing converts.** The column keeps instants and the API sends and reads them in UTC as before. Changing the zone
moves no data; only how a client reads and shows the wall time changes. A client reads `field.timeZone` ahead of its
own default.

## Not done

- **Notification rules in the field's zone.** `wasichai.notifications.zone` still counts "today" and windows in one
  server-side zone. Using the field's zone for a rule on a zoned field is likely right, and can come later without
  changing this contract.
- **An organization-level default zone.** The API telling a client its organization's zone would let the frontend drop
  `config.timeZone`. That is a separate decision; this one is only the per-field override.

## Consequences

- A `DATETIME` field can say whose wall clock it is, and every client reads the same answer.
- `custom_fields.time_zone` is a schema-parity deviation (D48). On a fresh database it sits before the `wasichai-gis`
  and `wasichai-files` columns, which move one place.
- The frontend follow-up is in wasichai-ui: `DynamicForm` reads `field.timeZone` before the form's prop and the app
  config.
