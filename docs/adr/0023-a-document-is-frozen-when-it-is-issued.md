# ADR-023: A document is frozen when it is issued

**Status**: accepted · 2026-09-20

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

An administrator writes a document template once — free text with the object's fields dropped into
the running prose, and related records as tables — and a record issues that document, by hand or on
reaching a workflow state. Each issue takes a correlative like `SGTM-2026-001`. Because a record can
go back a state and forward again, a record can issue the same document more than once; the one that
prevails is the last, and the earlier ones stay as history.

That is an administrative document, not a report. It is handed to someone, it is quoted by its
number, and it may be argued about a year later. Everything below follows from that.

## Decision

**D1 — Issuing freezes a copy, and the copy is JSON.** A `documents` row stores a `snapshot`
carrying the values that filled the document, the platform values already resolved, the related rows
as they were **and the template as it was**. HTML or a PDF can be regenerated from it at any time.

The template travels *inside* the snapshot, which is the part that is easy to get wrong: without it,
editing a type would silently rewrite every document it ever issued, and an archived document would
stop being evidence of anything. A reference that re-renders is cheaper and is not the same object.

**D2 — Platform values are resolved at issue time, never at read time.** `today` is a date in the
snapshot, not a placeholder. A document archived in 2024 printing today's date each time someone
opens it would be wrong in the field people look at first.

**D3 — The correlative is a table, not a sequence.** `document_counters` is keyed by (type, year) and
incremented with one `INSERT … ON CONFLICT DO UPDATE … RETURNING` inside the issuing transaction. A
Postgres sequence is not transactional: a failed issue would burn a number and leave a hole in a
correlative that somebody then has to explain. This one moves with the transaction.

The series runs **per type and per year**, which is why a type's sigla is unique per organization
rather than per object: the counter counts per type, so two types sharing a sigla would each issue
their own `SGTM-2026-001` and the number would stop naming one document.

**D4 — "The last one prevails" is a partial unique index, not a rule in a service.**

```sql
CREATE UNIQUE INDEX documents_one_valid_per_record
    ON wasichai.documents (document_type_id, record_id) WHERE status = 'VALID';
```

Issuing archives the current valid one and inserts the new one in the same transaction. Nothing —
no service, no migration, no hand-written SQL — can leave two valid documents of one type on one
record.

Two issues of one type on one record are serialised by a Postgres advisory lock held for the
transaction. Without it a double-click on *Issue* means one of the two dies on that index, and an
error is not what the administrator meant: they meant issue again, archiving the last.

**D5 — A type that has issued anything cannot be deleted.** `ON DELETE RESTRICT` on the foreign key,
and a 409 with a sentence in front of it. An issued document is a fact; the type it came from is a
setting, and a setting does not get to delete a fact.

**D6 — Field permissions do not apply to a document.** The record is read for issuing *without* the
caller's field filter, and the whole document is shown to anyone who may see it. The control is on
seeing the document at all.

This is a real departure: everywhere else in this platform, `readableBy` removes fields the caller
may not read, and the audit log goes out of its way not to become a way around field permissions.
A document is different because it is handed over as a whole. Two people opening the same number and
seeing different documents would mean one of them is not holding the document that was issued.

**D7 — A template is a node tree, never HTML.** It is stored the way the editor writes it —
ProseMirror's shape — and the client walks it to React elements. Nothing in this codebase renders
HTML it was handed and there is no sanitiser among its dependencies; a document template, which is
authored by one person and read by another, is the worst possible place to start.

The server never renders a template. It validates only what the template *names*: a field or a
relationship that does not exist would issue a document with a hole in it, and the hole would be
found by whoever the document was for.

**D8 — Issuing is history, and the entry points at the document.** The audit log predates documents:
three operations, none of which point anywhere. An issue is a fourth, `ISSUE`, and it is the only
entry that carries a link. The entry is written inside the issuing transaction, so a document never
exists without its history entry and the history never names a document that was not issued.

The link is `ON DELETE SET NULL`, not `CASCADE`: an audit entry is a fact about what happened and
must not vanish because the thing it names later did. It stops pointing, and stays.

## Consequences

A document is bigger than a reference would be, and a template change never reaches what was already
issued — which is the point, but it means fixing a typo in a template does not fix it in the
documents already out.

**The count against `MAX_COMPONENTS`-style bounds is different here:** a template of twenty fields is
twenty-one nodes, and the node cap is 5000, so long documents are fine and pathological ones are not.

D6 is the one to revisit if documents ever become something a wide audience reads rather than
something issued to a party. If that happens, the answer is a permission on the document type, not a
field filter at read time — the alternative reintroduces "two people, one number, two documents".

D3 leaves one accepted gap: a series is per year by the issuing instant in UTC. An organization that
wants its own timezone's new year would need the counter keyed by a date the organization owns, and
that is not built.

## Addendum — 2026-09-22: the PDF is the browser's job

A document prints from a plain print-ready page (`/documents/:id/print`) that reuses `DocumentView`
(D7) and calls `window.print()`. No server-side PDF generation was added: a second renderer in
Kotlin walking the same node tree would drift from `DocumentView` the first time one of them
learned a new mark or node type, and D7 exists specifically to keep there being one renderer.

Revisit this only if a PDF must be stored or attached automatically -- emailed out, put in a
workflow action, kept as its own artifact next to the snapshot. Printing on demand from the browser
needs none of that.
