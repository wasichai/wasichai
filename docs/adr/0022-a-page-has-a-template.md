# ADR-022: A page has a template

**Status**: accepted · 2026-09-19 · amends ADR-021

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).
>
> Moved from chawpi on 2026-09-26: identifiers renamed chawpi → wasichai; the decision is unchanged. See
> [ADR-032](0032-rebrand-to-wasichai-and-split-repositories.md) and [the origin page](../chawpi-origin.md).

## Context

ADR-021 made a page's tree free at every level: any container holds any children, to any depth, with
no shape imposed from outside. The canvas that edits it inherited that freedom — an administrator
opens a blank detail page and drags whatever they want, wherever they want. It works, but a blank
canvas suggests nothing: there is no starting point and no shared vocabulary between objects. What is
asked for is what Lightning App Builder gives an admin — a catalogue of templates with named regions
("header and left sidebar", "three regions"…) — so a page begins as something, not as nothing.

There is a second, unrelated cost the free tree left behind. Every container carries its own
`layout` inside the tree, except the page: its layout lived outside the tree, in a `layout` column on
`wasichai.pages`. That one exception already caused two bugs on the previous branch — the renderer
ignored the root's layout because it wasn't part of the tree it was walking, and the canvas had that
same layout wired to a database column instead of a node. A real `PAGE` node removes the root's
special case from the model, the validator, the renderer and the canvas all at once, because it is
no longer a special case: the root is a container like any other.

## Decision

**The tree stops being free at the first level.** `PageDefinition` no longer holds a list of
components; it holds one field, `page`, always a `PAGE` node, holding one `REGION` child per region
its template declares — no more, no fewer, in the template's order. This **reverses ADR-021's D1**
(a free tree) at the first level, knowingly. A page with a starting scaffold — a header, a main area,
a sidebar the admin can already picture — is worth a bounded root; a completely free canvas at every
level was worth more than a bounded one everywhere else, which is why the reversal stops at the
first level and goes no further.

**The scaffold is top-level only.** Inside a region the tree is exactly what ADR-021 made it:
`SECTION`, `TABS`, `TAB` and arbitrary nesting, validated the same way, bounded by the same guard
rails. Only `PAGE` and `REGION` are fixed; everything a region holds is still placed, moved and
nested freely.

The rule that enforces this is not "you may not add a region" — there is no such rule written down.
A page's region list is required to **equal** its template's region list, exactly, including order.
A set that must equal another set is one nothing can add to, remove from, rename or reorder; the
scaffold is a consequence of that one equality check, not a family of separate refusals.

**The catalogue is code, not a table.** `PageTemplate` is a Kotlin enum of rows-of-regions on a
12-column grid — no CRUD, no user-authored templates, no `CUSTOM` category. Twelve columns divide
exactly by 2, 3 and 4, so halves, thirds and a 2:1 sidebar are all whole spans, and the same
description serves the validator, the renderer and the client-drawn preview. Region names are a
**global** vocabulary (`HEADER`, `MAIN`, `LEFT`, `CENTER`, `RIGHT`), not one vocabulary per template,
and `MAIN` is present in every template. That is deliberate: an admin's main content never needs a new home
when the template changes, because every template has somewhere for it to stay; only a header or a
sidebar can ever be orphaned by a template change.

**`layout` leaves the `wasichai.pages` row.** The template supersedes it — a page's layout is now the
column count and span of the regions its template declares, which says more than `single-column` /
`two-column` ever could and is consultable without walking JSON ("which pages use `three-regions`" is
a `SELECT` on the new `template` column, not a tree walk). `PageLayout` itself is untouched: every
`SECTION`, `TABS`, `TAB` and now `REGION` still carries its own, for how it lays out **its own**
children. A region can still be two columns inside, exactly like a section could before it.

**`MAX_DEPTH` rises from 10 to 12.** The scaffold spends two levels on `PAGE` and `REGION`. Leaving
the bound at 10 would have quietly cut the administrator's free budget from 10 to 8; raising it to 12
means the free part of the tree keeps exactly the ten levels ADR-021 gave it.

**V11 discards every stored page; it does not migrate them** — worth its own paragraph, because it
is the one irreversible thing here. "Is this definition valid under the new shape" is a question only
the Kotlin validator can answer: it needs the object's fields, relationships, forms and workflow
transitions, none of which a SQL migration has access to. A jsonb-level approximation inside the
migration would forgive rows the validator would refuse the moment anyone touched them again, which
is worse than not migrating at all. So `V11__a_page_has_a_template.sql` deletes every row in
`wasichai.pages`, drops the `layout` column, and adds `template text NOT NULL DEFAULT 'one-region'` —
delete first, so the `NOT NULL` column needs no backfill. There is no guard on the `DELETE`: at the
point V11 runs, no stored row can already have the new shape, because the new shape did not exist
before it. And there is no `CHECK` on `template`: the catalogue is code, so a tenth template must
stay a code change, never a migration.

**The trade accepted knowingly: the server cannot tell "moved" from "deleted".** The client, not the
server, performs the orphan relocation when an administrator changes template — it reads the current
tree, decides where a dying region's children go, and sends an already-valid tree that satisfies the
region-equality rule on its own. The server only ever validates what it receives; it has no memory of
what the tree looked like a request ago, and the design gives it no way to acquire one without
turning it into something more than a validator. A client with a bug that simply discards a dying
region's children — instead of moving them — produces a payload the server accepts exactly as
happily as a correct one. There is no server-side safety net possible here, given the decision to let
the client own the move. This is recorded as a known, accepted cost, not an oversight: the
alternative was a server that remembers previous trees or diffs old against new, which is a
different and heavier kind of validator than the one ADR-021 already built.

**Form factors are deferred.** Salesforce's `flexipage:formfactor` lets a region's content vary by
device shape. It is deliberately left out: the catalogue is code today, and per-form-factor content
is a templating concern that belongs with a data model, not with hardcoded enum entries. The
growth path is left open rather than closed off — `TemplateRegion` is shaped so an optional
`formFactors` field can be added to it later without breaking the wire, if and when the catalogue
ever moves from code to stored rows.

**What was lost.** V11's delete is total: every hand-configured page in every environment is replaced
by the generated default the next time it resolves — invented tab names, sections, two-column
layouts, maps pointed at a specific geometry, chosen related lists, text and configured action
buttons, all of it. `wasichai.forms` and `wasichai.views` are untouched; this migration reaches `pages`
only. One case deserves saying on its own, because it is easy to miss: a `FORM` component that named
a saved form loses that link. The form row itself is not touched and is not deleted — but no page
points at it anymore, until an administrator opens the page and picks it again. "Your forms are
safe" is true. "Your pages still use them" is not.

## Consequences

- `PageComponent.region` is a new field, a `PageRegion?` set only on `REGION` nodes. It is
  deliberately not `title`: `title` is free text that `walk()` trims and can null out, and a blank
  title would silently destroy which region a node represents.
- Validation gains a matched set of rules keyed on `PAGE`/`REGION` — no root, a root that isn't
  `PAGE`, a `PAGE` found mid-tree, a non-`REGION` child of `PAGE`, a `REGION` outside a `PAGE`, and
  the region-equality check itself — each with its own message, alongside the existing `TABS`/`TAB`
  rules from ADR-021.
- An empty region is legal and must stay legal: refusing it would make the first save after picking a
  brand-new template impossible, since every region starts empty.
- `column` and `layout` on `PAGE` and `REGION` are normalised rather than refused (`column = 1`,
  `PAGE.layout = SINGLE_COLUMN`). Refusing them would be a trap: `toRequest()` re-emits the stored
  node on every `PUT` that omits `definition`, so an explicit value surviving on `PAGE` would break a
  plain label edit forever. `REGION.layout` is the exception — it is real and checked, because a
  region can be two columns inside, like a section.
- `generate()` always builds `one-region`. It is the only template whose region is guaranteed
  non-empty for any object — the others would gift a permanently empty `HEADER` or sidebar to any
  object without a workflow or related records, which the admin has no way to remove. `generate()`
  also has no administrator intent to read: a template is an authoring choice, and deriving one from
  metadata would be guessing.
- `GET /api/metadata/page-templates` exposes the catalogue statically and tenant-free, next to
  `/api/metadata/system-fields` — the same kind of fixed list a client needs to build a picker from.
  It does not live under `/api/pages/`, where `GET /api/pages/{name}` would shadow it and where
  `VALID_PAGE_NAME` would allow a page literally named `templates`.
- The bounded tree that ADR-021 considered and rejected for the *whole* page is, in effect, what this
  ADR adopts for the *first level only* — proof that the free-tree decision and the bounded-scaffold
  decision were never mutually exclusive, only differently scoped.
- `REGION.layout = two-column` is accepted, stored and drawn correctly by the renderer, but the
  canvas cannot produce it: no `REGION` is selectable, so its layout can never be set, and a template
  swap always mints `single-column`. The canvas draws every region single-column regardless of what
  is stored. This is drift, not a live bug — nothing today can write the shape the canvas cannot
  draw — and is left as-is rather than built for speculatively.
