# ADR-018: Workflows are edited on a canvas, and the canvas remembers where things are

**Status**: accepted · 2026-09-18

> Imported from sapgis on 2026-09-25. Identifiers renamed sapgis → chawpi; the decision is unchanged. See [sapgis origin](../sapgis-origin.md).

## Context

ADR-013 settled what a workflow is: states and transitions, no BPM engine. The editor that shipped
with it was two lists of form rows plus a read-only "Summary" card that printed
`state → label → target` as text, and it said so on purpose:

> `// form-based workflow editor: states, transitions and a read-only picture of the graph.`
> `// not a canvas, on purpose.`

That held while flows were three states long. It stops holding sooner than expected: a definition is
a directed graph, and the one thing a list of `from`/`to` dropdowns cannot show is its shape. Reading
a seven-state flow meant drawing it on paper first, and rearranging one meant editing fields while
guessing at the result.

## Decision

**The graph is the editor.** States are dragged, a transition is made by dragging from one state to
another, and a panel on the right edits whatever is selected. The three form cards are gone; the
rules that lived inside them did not move to the canvas, they moved to `workflowGraph.ts`.

**Coordinates live in the definition**, as optional `x`/`y` on each state. `definition` is already a
single `jsonb` column, so this needed no migration and no second table. The alternative — a layout
table keyed by state name — would have put presentation one join away from the thing it describes
and given rename two places to go wrong. A state with no coordinates is laid out by the client
(breadth-first from the initial state, a column per hop) and the next save stores where it landed, so
every workflow written before this change opens readable.

Half a position is refused on the way in: if only one of `x`/`y` arrives, both are stored null. A
state at `x=40, y=null` is not a placed state, and pretending otherwise would put it somewhere
nobody chose.

**The picture orders the list.** The form editor had up/down buttons on each state, and that order
is stored and read elsewhere — the automation builder lists a workflow's states in it. A canvas has
no up and down, so on save the states are sorted into reading order: left to right, then top to
bottom. Arranging the diagram is how the order is now chosen, which is one rule instead of two
buttons that duplicated what position already says.

**The canvas refuses what the server refuses.** Dragging out of a FINAL state is rejected on the
spot, naming the state, because `WorkflowService.validate` rejects it too. This is not a second
opinion — it is the same one, delivered before the round trip.

**`@xyflow/react` instead of hand-written SVG.** It brings pan, zoom and connection handles, which is
most of what a canvas is, and it is the only runtime dependency this phase adds. Its minimap is not
used: these graphs fit on screen, so a map of what is already fully visible would only hide the
corner it sits in — which it did, the first time a state was dragged there.

## Consequences

- **Interaction on the canvas is not covered by the test suite.** xyflow measures its container with
  a `ResizeObserver` and renders nothing when that measures zero, which is all jsdom ever reports.
  Tests that rendered it would assert that a `<div>` exists. So the decisions moved out of the
  component — layout, connection legality, definition↔graph conversion, coordinate writing are pure
  functions in `workflowGraph.ts` with their own tests — and `WorkflowBuilderPage.test.tsx` swaps the
  canvas for a double that reports the props it received and fires the callbacks a pointer would.
  Dragging, zooming and edge routing are verified by looking at them.
- The definition now carries presentation beside behaviour. Anything reading it for behaviour
  (`WorkflowService`, `AutomationRules`, the agent's `available_transitions`) ignores the two new
  fields, and `validate()` does not check them: a wrong number moves a box, it does not move a record.
- A transition is made by dragging and there is no "add transition" button. Everything the old button
  could build is reachable that way, including a transition from a state back to itself; what is lost
  is a label announcing the feature, which the empty inspector says instead.
- Renaming a state still happens in the client, rewriting its transitions in the draft before the PUT,
  exactly as the form editor did. The server keeps receiving a coherent definition.
- One dependency more. `@xyflow/react` pulls a handful of `d3-*` packages and `zustand`; the bundle
  grows, in an application that already ships MapLibre.
