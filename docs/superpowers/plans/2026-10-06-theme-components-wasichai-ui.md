# Theme components, wasichai-ui: `Alert` and `NavTree` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `Alert` joins `@wasichai/ui` and `NavTree` joins `@wasichai/core`, painted under `portal-tributario` by two new
partials of the library's sheet, so srtm-ui and caja-ui can delete their copies; the decision is recorded in wasichai's
docs.

**Architecture:** `Alert` is srtm-ui's `Alerta` renamed, router-free, next to the primitives. `NavTree` is srtm-ui's
`ArbolNav` renamed, in core's `shell/` because it needs `react-router`; its node types and the current-leaf rule live in
`shell/navTreeNodes.ts`. Both carry `data-slot` hooks; `alerts.css` and `nav.css` join
`packages/ui/src/themes/portal-tributario/` inside the sheet's `@scope`. Their words are three `common.*` keys in core's
bundle.

**Tech Stack:** React 19, TypeScript 5.9, Tailwind CSS 4, react-router 8, react-i18next, lucide-react, vitest +
testing-library, yarn 1 workspaces.

**Spec:** [`docs/superpowers/specs/2026-10-06-theme-components-design.md`](../specs/2026-10-06-theme-components-design.md)
(wasichai). Issue: [wasichai/wasichai-ui#14](https://github.com/wasichai/wasichai-ui/issues/14).

**Repositories:** tasks 1–6 in `~/IdeaProjects/wasichai-ui` on a branch `feat/ui-componentes-tema` off `origin/dev`;
task 7 in `~/IdeaProjects/wasichai` on the branch `docs/theme-components` (it already holds the spec and these plans).

## Global Constraints

- `@wasichai/ui` imports nothing from `@wasichai/core` and nothing from `react-router`. No new dependency in any package.
- Identifiers and comments in English; comments caveman style: short, say why.
- `.editorconfig`: TS/TSX 2 spaces, CSS and JSON 4 spaces, max 160 columns, LF, final newline. `yarn format` before
  each commit.
- Light and dark do not change: same classes, same markup as srtm-ui's components; only `data-ui` becomes `data-slot`
  and `data-tono` becomes `data-tone`.
- Every partial rule sits inside `@scope ([data-theme='portal-tributario']) to ([data-theme]:not([data-theme='portal-tributario']))`,
  unlayered, hooked on a `data-slot`.
- `data-slot` names (public API from 0.5.0): `alert`, `alert-text`, `alert-dismiss`, `nav-tree`, `nav-tree-group`,
  `nav-tree-leaf`, `nav-tree-caret`.
- Strings: `common.dismissAlert` = "Entendido, cerrar el aviso" / "Got it, dismiss this notice"; `common.goHome` = "Ir al
  inicio" / "Go to the home page"; `common.hideMenu` = "Ocultar el menú" / "Hide the menu".
- Conventional Commits, enforced by commitlint. Every commit message ends with:

  ```
  Refs wasichai/wasichai-ui#14

  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
  ```

- PRs target `dev`, never `main`. Pushing a `v*-dev.*` tag publishes: ask the user first.

## Review Focus

- A leaf whose own route also matches another leaf's `alsoAt` (`/infracciones/cuis` against `/infracciones/:id`): the
  own route wins (Task 3, `currentNavTreeLeaf` table).
- A path that only shares a string prefix with a leaf (`/contribuyentesx`): no leaf is current (Task 3).
- An external leaf (the administration): never current, and a click on it does not call `onNavigate`, because the whole
  page reloads (Task 4).
- An alert whose tone changes while mounted (a ternary `tone`): its role follows, `status` to `alert` (Task 1).
- Two groups with the same label under different parents: their keys differ because they carry the path
  (`Tributos/Impuesto predial`) (Task 4).

---

### Task 1: `Alert` in `@wasichai/ui`, with its words in core

**Files:**
- Create: `packages/ui/src/alert.tsx`
- Create: `packages/ui/src/alert.test.tsx`
- Modify: `packages/ui/src/index.ts`
- Modify: `packages/core/src/i18n/locales/es/common.json` (the `common` object)
- Modify: `packages/core/src/i18n/locales/en/common.json` (the `common` object)
- Modify: `packages/core/src/i18n/sharedPrimitives.test.tsx`

**Interfaces:**
- Produces: `Alert(props: AlertProps)`, `type AlertTone = 'success' | 'warning' | 'danger' | 'notice'`,
  `interface AlertProps { tone: AlertTone; title?: ReactNode; children: ReactNode; onDismiss?: () => void; className?: string }`,
  all exported from `@wasichai/ui`. Keys `common.dismissAlert`, `common.goHome`, `common.hideMenu` in core's bundle.

- [ ] **Step 1: Branch off `dev`**

```bash
cd ~/IdeaProjects/wasichai-ui
git fetch origin
git switch -c feat/ui-componentes-tema origin/dev
yarn install
```

- [ ] **Step 2: Write the failing test**

`packages/ui/src/alert.test.tsx` (no i18n instance in ui's tests: `t()` answers its key, as in `confirm-dialog.test.tsx`):

```tsx
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Alert, type AlertTone } from './alert'

// no i18n instance here: t() answers its key. the words are asserted in core's sharedPrimitives test
const DISMISS = 'common.dismissAlert'

describe('Alert', () => {
  it.each<[AlertTone, string, string]>([
    ['success', 'status', 'text-success'],
    ['warning', 'status', 'text-warning'],
    ['danger', 'alert', 'text-danger'],
    ['notice', 'status', 'text-notice']
  ])('draws %s as a %s, text in its colour', (tone, role, colour) => {
    render(<Alert tone={tone}>Hecho.</Alert>)
    const alert = screen.getByRole(role)
    expect(alert).toHaveTextContent('Hecho.')
    expect(alert).toHaveClass('text-sm', colour)
    expect(alert).toHaveAttribute('data-slot', 'alert')
    expect(alert).toHaveAttribute('data-tone', tone)
  })

  // a ternary tone (registered in time or late) switches how it is announced
  it('follows a tone that changes while mounted', () => {
    const { rerender } = render(<Alert tone="success">Registrado.</Alert>)
    expect(screen.getByRole('status')).toHaveAttribute('data-tone', 'success')
    rerender(<Alert tone="danger">Registrado.</Alert>)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveAttribute('data-tone', 'danger')
  })

  it('writes the title in bold before the text, inside the text hook', () => {
    render(
      <Alert tone="warning" title="Atención.">
        Falta el año.
      </Alert>
    )
    const text = screen.getByRole('status').querySelector('[data-slot="alert-text"]')
    expect(text).toHaveTextContent('Atención. Falta el año.')
    expect(screen.getByText('Atención.').tagName).toBe('STRONG')
  })

  it("adds the place's classes to the classic look", () => {
    render(
      <Alert tone="danger" className="rounded-md bg-danger/10 px-3 py-2">
        No se guardó.
      </Alert>
    )
    expect(screen.getByRole('alert')).toHaveClass('text-sm', 'text-danger', 'rounded-md', 'bg-danger/10', 'px-3', 'py-2')
  })

  it('has no button without onDismiss', () => {
    render(<Alert tone="notice">Aviso.</Alert>)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('dismisses with its check button', async () => {
    const onDismiss = vi.fn()
    render(
      <Alert tone="notice" onDismiss={onDismiss}>
        Aviso.
      </Alert>
    )
    const button = screen.getByRole('button', { name: DISMISS })
    expect(button).toHaveAttribute('data-slot', 'alert-dismiss')
    expect(button).toHaveAttribute('type', 'button')
    expect(button.querySelector('svg')).not.toBeNull()
    await userEvent.click(button)
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
```

- [ ] **Step 3: Run it to see it fail**

Run: `yarn --cwd packages/ui vitest run src/alert.test.tsx`
Expected: FAIL, `Failed to resolve import "./alert"`.

- [ ] **Step 4: Write `Alert`**

`packages/ui/src/alert.tsx`:

```tsx
import { Check } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { cn } from './cn'

export type AlertTone = 'success' | 'warning' | 'danger' | 'notice'

export interface AlertProps {
  tone: AlertTone
  // bold, at the start: "Atención.", "La sesión caducó."
  title?: ReactNode
  children: ReactNode
  // shows a dismiss button (a check)
  onDismiss?: () => void
  // what the place adds to the classic look: a box, a margin, the alignment
  className?: string
}

// the classic look: the text in its tone's colour. a box is a theme sheet's (portal-tributario paints one by data-slot
// and data-tone), so light and dark stay text
const TEXT: Record<AlertTone, string> = {
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  notice: 'text-notice'
}

// a message with a tone: danger interrupts (alert), the rest is announced politely (status)
export function Alert({ tone, title, children, onDismiss, className }: AlertProps) {
  const { t } = useTranslation()
  return (
    <div role={tone === 'danger' ? 'alert' : 'status'} data-slot="alert" data-tone={tone} className={cn('text-sm', TEXT[tone], className)}>
      <span data-slot="alert-text">
        {title && <strong className="font-bold">{title}</strong>}
        {title && ' '}
        {children}
      </span>
      {onDismiss && (
        <button
          type="button"
          data-slot="alert-dismiss"
          aria-label={t('common.dismissAlert')}
          onClick={onDismiss}
          className="ml-2 inline-flex rounded p-0.5 align-middle opacity-80 hover:opacity-100"
        >
          <Check className="size-4" />
        </button>
      )}
    </div>
  )
}
```

In `packages/ui/src/index.ts`, after the `cn` line:

```ts
export { Alert, type AlertProps, type AlertTone } from './alert'
```

- [ ] **Step 5: Run it to see it pass**

Run: `yarn --cwd packages/ui vitest run src/alert.test.tsx`
Expected: PASS, 9 tests.

- [ ] **Step 6: Write the failing test of the words**

Append to `packages/core/src/i18n/sharedPrimitives.test.tsx` (add `Alert` to its `@wasichai/ui` import):

```tsx
describe('Alert strings', () => {
  it('names its dismiss button', () => {
    renderWithProviders(
      <Alert tone="notice" onDismiss={noop}>
        Aviso.
      </Alert>
    )
    expect(screen.getByRole('button', { name: 'Entendido, cerrar el aviso' })).toBeInTheDocument()
  })

  it('names it in English when the app is in English', () => {
    renderWithProviders(
      <Alert tone="notice" onDismiss={noop}>
        Notice.
      </Alert>,
      { language: 'en' }
    )
    expect(screen.getByRole('button', { name: 'Got it, dismiss this notice' })).toBeInTheDocument()
  })
})
```

Run: `yarn --cwd packages/core vitest run src/i18n/sharedPrimitives.test.tsx`
Expected: FAIL, no button named "Entendido, cerrar el aviso" (it reads `common.dismissAlert`).

- [ ] **Step 7: Add the three keys to core's bundle**

`NavTree` (Task 4) reads the other two; they go in now so the bundle changes once. In
`packages/core/src/i18n/locales/es/common.json`, inside the `"common"` object, after `"close": "Cerrar",`:

```json
        "dismissAlert": "Entendido, cerrar el aviso",
        "goHome": "Ir al inicio",
        "hideMenu": "Ocultar el menú",
```

In `packages/core/src/i18n/locales/en/common.json`, inside `"common"`, after `"close": "Close",`:

```json
        "dismissAlert": "Got it, dismiss this notice",
        "goHome": "Go to the home page",
        "hideMenu": "Hide the menu",
```

- [ ] **Step 8: Run both packages' tests**

Run: `yarn --cwd packages/core vitest run src/i18n && yarn --cwd packages/ui test`
Expected: PASS (the i18n tests that compare `es` and `en` keys stay green: the three keys are in both).

- [ ] **Step 9: Format, check and commit**

```bash
yarn format
yarn --cwd packages/ui lint && yarn --cwd packages/core lint
git add packages/ui/src/alert.tsx packages/ui/src/alert.test.tsx packages/ui/src/index.ts \
  packages/core/src/i18n/locales/es/common.json packages/core/src/i18n/locales/en/common.json \
  packages/core/src/i18n/sharedPrimitives.test.tsx
git commit -F - <<'EOF'
feat(ui): Alert, a message in one of four tones

srtm-ui's Alerta, which caja-ui uses too: success, warning, danger or notice, an optional bold title and a dismiss
check. Light and dark draw it as text in its tone's colour; a theme sheet paints a box by data-slot="alert" and
data-tone. The dismiss label and NavTree's two words join core's common bundle.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 2: the alerts partial of the `portal-tributario` sheet

**Files:**
- Create: `packages/ui/src/themes/portal-tributario/alerts.css`
- Modify: `packages/ui/src/themes/portal-tributario/index.css`
- Modify: `packages/ui/src/themes/portalTributario.test.ts`

**Interfaces:**
- Consumes: the hooks of Task 1 (`data-slot="alert"` with `data-tone`, `alert-text`, `alert-dismiss`).
- Produces: `PARTIALS`, `SLOTS` and the allowed attributes of `portalTributario.test.ts`, which Task 5 extends with
  `nav.css` and the `nav-tree*` slots.

- [ ] **Step 1: Write the failing tests**

In `packages/ui/src/themes/portalTributario.test.ts`:

1. `const PARTIALS = ['controls.css', 'tables.css', 'tabs.css', 'alerts.css']`
2. Above `SLOTS`, the comment becomes `// the hooks the library's components put (a sheet only styles those, rule 6): ui's and core's`,
   and `SLOTS` gains, after `'tabs-content'`: `'alert'`, `'alert-text'`, `'alert-dismiss'`.
3. Add `const TONES = ['success', 'warning', 'danger', 'notice']` after `SIZES`.
4. In `it("hook only the library's own components", …)`, allow `data-tone` and check its values:

```ts
    for (const [, name] of sheet.matchAll(/\[(data-[a-z-]+)/g))
      expect(['data-theme', 'data-slot', 'data-variant', 'data-size', 'data-tone'], name).toContain(name)
    for (const slot of values('data-slot')) expect(SLOTS, slot).toContain(slot)
    for (const variant of values('data-variant')) expect(VARIANTS, variant).toContain(variant)
    for (const size of values('data-size')) expect(SIZES, size).toContain(size)
    for (const tone of values('data-tone')) expect(TONES, tone).toContain(tone)
```

5. Append:

```ts
describe('alerts.css', () => {
  const { unlayered: css } = layers(read('alerts.css'))
  const ALERT = "[data-slot='alert']"

  // bootstrap 3's borders (no token); background and text are the tone's tokens
  it.each([
    ['success', '#d6e9c6'],
    ['warning', '#faebcc'],
    ['danger', '#ebccd1'],
    ['notice', '#e8e0c4']
  ])('paints %s with its soft background, its text and its border', (tone, border) => {
    const box = rule(css, `${ALERT}[data-tone='${tone}']`)
    expect(box.get('background')).toBe(`var(--${tone}-soft)`)
    expect(box.get('color')).toBe(`var(--${tone})`)
    expect(box.get('border-color')).toBe(border)
  })

  it('draws the box as the prototype', () => {
    const box = rule(css, ALERT)
    expect(box.get('display')).toBe('flex')
    expect(box.get('padding')).toBe('14px 18px')
    expect(box.get('border')).toBe('1px solid')
    expect(box.get('border-radius')).toBe('3px')
    expect(box.get('font-size')).toBe('14.5px')
    expect(box.get('line-height')).toBe('1.6')
  })

  // the text takes the width, so the check stays at the top right
  it('keeps the dismiss check at the top right', () => {
    expect(rule(css, "[data-slot='alert-text']").get('flex')).toBe('1')
    const dismiss = rule(css, "[data-slot='alert-dismiss']")
    expect(dismiss.get('margin-left')).toBe('0')
    expect(dismiss.get('padding')).toBe('3px')
  })

  it('keeps AA for every tone on its box', () => {
    const pairs = ['success', 'warning', 'danger', 'notice'].map((tone): [string, string, string] => [tone, `var(--${tone})`, `var(--${tone}-soft)`])
    expect(failing(pairs)).toEqual([])
  })
})
```

- [ ] **Step 2: Run them to see them fail**

Run: `yarn --cwd packages/ui vitest run src/themes/portalTributario.test.ts`
Expected: FAIL: `ENOENT … alerts.css`, and the folder listing lacks `alerts.css`.

- [ ] **Step 3: Write the partial and import it**

`packages/ui/src/themes/portal-tributario/alerts.css`:

```css
/* alerts: the prototype's boxes, bootstrap 3's palette, in four tones. unlayered, so they win over tailwind's
   utilities (Alert's classic look and the classes each place gives it); hooked on Alert's data-slot and data-tone.
   background and text are tokens (their contrast is portalTributario.test.ts's); the borders have no token. scoped to
   the theme, and never into a subtree pinned to another theme */
@scope ([data-theme='portal-tributario']) to ([data-theme]:not([data-theme='portal-tributario'])) {
    [data-slot='alert'] {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 14px 18px;
        border: 1px solid;
        border-radius: 3px;
        font-size: 14.5px;
        line-height: 1.6;
    }

    [data-slot='alert'][data-tone='success'] {
        border-color: #d6e9c6;
        background: var(--success-soft);
        color: var(--success);
    }

    [data-slot='alert'][data-tone='warning'] {
        border-color: #faebcc;
        background: var(--warning-soft);
        color: var(--warning);
    }

    [data-slot='alert'][data-tone='danger'] {
        border-color: #ebccd1;
        background: var(--danger-soft);
        color: var(--danger);
    }

    [data-slot='alert'][data-tone='notice'] {
        border-color: #e8e0c4;
        background: var(--notice-soft);
        color: var(--notice);
    }

    /* the text takes the width; the dismiss check stays at the top right */
    [data-slot='alert-text'] {
        flex: 1;
        min-width: 0;
    }

    [data-slot='alert-dismiss'] {
        margin-left: 0;
        padding: 3px;
    }
}
```

In `packages/ui/src/themes/portal-tributario/index.css`, after `@import './tabs.css';`:

```css
@import './alerts.css';
```

and the header comment's last line becomes
`dark never change, nor a subtree pinned to another theme) and hooked on the components' data-slot (ui's and core's) */`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `yarn --cwd packages/ui vitest run src/themes`
Expected: PASS.

- [ ] **Step 5: Format, check and commit**

```bash
yarn format
yarn --cwd packages/ui lint
git add packages/ui/src/themes
git commit -F - <<'EOF'
feat(ui): portal-tributario paints Alert as the prototype's boxes

alerts.css joins the sheet: the box, and per tone its soft background, its text and Bootstrap 3's border, on
data-slot="alert" and data-tone, inside the theme's @scope.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 3: the tree's nodes and its current leaf, in core

**Files:**
- Create: `packages/core/src/shell/navTreeNodes.ts`
- Create: `packages/core/src/shell/navTreeNodes.test.ts`
- Modify: `packages/core/src/index.ts`

**Interfaces:**
- Produces (exported from `@wasichai/core`):

```ts
interface NavTreeLeaf { label: string; to: string; alsoAt?: string[]; external?: boolean; icon?: LucideIcon }
interface NavTreeGroup<L extends NavTreeLeaf = NavTreeLeaf> { label: string; children: NavTreeNode<L>[] }
type NavTreeNode<L extends NavTreeLeaf = NavTreeLeaf> = NavTreeGroup<L> | L
isNavTreeGroup<L extends NavTreeLeaf>(node: NavTreeNode<L>): node is NavTreeGroup<L>
navTreeLeaves<L extends NavTreeLeaf>(nodes: NavTreeNode<L>[]): L[]
currentNavTreeLeaf<L extends NavTreeLeaf>(nodes: NavTreeNode<L>[], pathname: string): L | undefined
```

- [ ] **Step 1: Write the failing test**

`packages/core/src/shell/navTreeNodes.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { currentNavTreeLeaf, isNavTreeGroup, navTreeLeaves, type NavTreeLeaf, type NavTreeNode } from './navTreeNodes'

// srtm-ui's tree, cut down: a leaf with alsoAt, one under a subgroup, two whose routes nest, an external one at the root
const NODES: NavTreeNode[] = [
  {
    label: 'Contribuyentes',
    children: [
      { label: 'Buscar contribuyentes', to: '/contribuyentes' },
      { label: 'Nuevo contribuyente', to: '/contribuyentes/nuevo' }
    ]
  },
  { label: 'Declaraciones', children: [{ label: 'Nueva declaración', to: '/declaraciones/nueva', alsoAt: ['/contribuyentes/:id/declaraciones/nueva'] }] },
  {
    label: 'Infracciones',
    children: [
      { label: 'Expedientes', to: '/infracciones', alsoAt: ['/infracciones/:id'] },
      { label: 'CUIS', to: '/infracciones/cuis' }
    ]
  },
  { label: 'Tributos', children: [{ label: 'Impuesto predial', children: [{ label: 'Cuenta corriente', to: '/cuenta' }] }] },
  { label: 'Administración', to: '/admin', external: true }
]

describe('isNavTreeGroup', () => {
  it('tells a group from a leaf', () => {
    expect(NODES.map(isNavTreeGroup)).toEqual([true, true, true, true, false])
  })
})

describe('navTreeLeaves', () => {
  it('lists every leaf in order, subgroups and external ones included', () => {
    expect(navTreeLeaves(NODES).map((leaf) => leaf.label)).toEqual([
      'Buscar contribuyentes',
      'Nuevo contribuyente',
      'Nueva declaración',
      'Expedientes',
      'CUIS',
      'Cuenta corriente',
      'Administración'
    ])
  })
})

describe('currentNavTreeLeaf', () => {
  it.each([
    ['/', undefined],
    ['/contribuyentes', 'Buscar contribuyentes'],
    // under a leaf's route: the longest start wins
    ['/contribuyentes/123', 'Buscar contribuyentes'],
    ['/contribuyentes/nuevo', 'Nuevo contribuyente'],
    // alsoAt over a shorter start
    ['/contribuyentes/123/declaraciones/nueva', 'Nueva declaración'],
    ['/declaraciones/nueva', 'Nueva declaración'],
    // a page with no leaf of its own
    ['/declaraciones/d1', undefined],
    ['/infracciones/0b5e8f1a', 'Expedientes'],
    // its own route over another leaf's alsoAt: /infracciones/:id matches /infracciones/cuis too
    ['/infracciones/cuis', 'CUIS'],
    ['/cuenta/2026', 'Cuenta corriente'],
    // a string prefix is not a start of the route
    ['/contribuyentesx', undefined],
    // an external leaf is another app: never current
    ['/admin', undefined]
  ])('on %s is %s', (path, label) => {
    expect(currentNavTreeLeaf(NODES, path)?.label).toBe(label)
  })

  // an app's leaves keep their own fields (caja-ui's clave), and identity holds (its breadcrumb trail compares them)
  it("returns the app's own leaf", () => {
    interface Hoja extends NavTreeLeaf {
      clave: string
    }
    const caja: Hoja = { clave: 'caja', label: 'Caja', to: '/caja' }
    const nodes: NavTreeNode<Hoja>[] = [{ label: 'Tesorería', children: [caja] }]
    const current = currentNavTreeLeaf(nodes, '/caja')
    expect(current).toBe(caja)
    expect(current?.clave).toBe('caja')
  })
})
```

- [ ] **Step 2: Run it to see it fail**

Run: `yarn --cwd packages/core vitest run src/shell/navTreeNodes.test.ts`
Expected: FAIL, `Failed to resolve import "./navTreeNodes"`.

- [ ] **Step 3: Write the nodes and the rule**

`packages/core/src/shell/navTreeNodes.ts` (not `navTree.ts`: on a case-insensitive file system `./NavTree` would
resolve to it):

```ts
import type { LucideIcon } from 'lucide-react'
import { matchPath } from 'react-router'

// a tree menu (NavTree): groups, with subgroups optionally, and leaves. an app extends the leaf with its own fields

export interface NavTreeLeaf {
  label: string
  to: string
  // route patterns (react-router's) that draw this leaf's page too
  alsoAt?: string[]
  // another app: a plain <a>, loaded in full, never current
  external?: boolean
  // drawn only by a leaf at the root, where a group has its caret
  icon?: LucideIcon
}

export interface NavTreeGroup<L extends NavTreeLeaf = NavTreeLeaf> {
  label: string
  children: NavTreeNode<L>[]
}

export type NavTreeNode<L extends NavTreeLeaf = NavTreeLeaf> = NavTreeGroup<L> | L

export const isNavTreeGroup = <L extends NavTreeLeaf>(node: NavTreeNode<L>): node is NavTreeGroup<L> => 'children' in node

export const navTreeLeaves = <L extends NavTreeLeaf>(nodes: NavTreeNode<L>[]): L[] =>
  nodes.flatMap((node) => (isNavTreeGroup(node) ? navTreeLeaves(node.children) : [node]))

// the leaf current on a path: the one whose route is the path, else one whose alsoAt matches it, else the one whose
// route is the longest start of it. own route first: /infracciones/:id matches /infracciones/cuis too. a page with no
// leaf of its own has none, an external leaf is never current
export function currentNavTreeLeaf<L extends NavTreeLeaf>(nodes: NavTreeNode<L>[], pathname: string): L | undefined {
  const own = navTreeLeaves(nodes).filter((leaf) => !leaf.external)
  const exact = own.find((leaf) => leaf.to === pathname)
  if (exact) return exact
  const byPattern = own.find((leaf) => leaf.alsoAt?.some((pattern) => matchPath(pattern, pathname)))
  if (byPattern) return byPattern
  return own
    .filter((leaf) => pathname.startsWith(`${leaf.to}/`))
    .reduce<L | undefined>((best, leaf) => (!best || leaf.to.length > best.to.length ? leaf : best), undefined)
}
```

In `packages/core/src/index.ts`, after `export { AppShell } from './shell/AppShell'`:

```ts
export {
  currentNavTreeLeaf,
  isNavTreeGroup,
  navTreeLeaves,
  type NavTreeGroup,
  type NavTreeLeaf,
  type NavTreeNode
} from './shell/navTreeNodes'
```

- [ ] **Step 4: Run it to see it pass**

Run: `yarn --cwd packages/core vitest run src/shell/navTreeNodes.test.ts src/boundaries.test.ts`
Expected: PASS (15 tests in `navTreeNodes.test.ts`; the boundaries stay green).

- [ ] **Step 5: Format, check and commit**

```bash
yarn format
yarn --cwd packages/core lint
git add packages/core/src/shell/navTreeNodes.ts packages/core/src/shell/navTreeNodes.test.ts packages/core/src/index.ts
git commit -F - <<'EOF'
feat(core): the nodes of a tree menu and its current leaf

NavTreeLeaf, NavTreeGroup and NavTreeNode, generic over the app's own leaf, with isNavTreeGroup, navTreeLeaves and
currentNavTreeLeaf: srtm-ui's latest rule, its own route first, then alsoAt, then the longest start of the path.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 4: `NavTree` in core

**Files:**
- Create: `packages/core/src/shell/NavTree.tsx`
- Create: `packages/core/src/shell/NavTree.test.tsx`
- Modify: `packages/core/src/index.ts`

**Interfaces:**
- Consumes: Task 3's types and `currentNavTreeLeaf`, `isNavTreeGroup`; Task 1's `common.goHome` and `common.hideMenu`.
- Produces: `NavTree<L extends NavTreeLeaf>(props: NavTreeProps<L>)` and

```ts
interface NavTreeProps<L extends NavTreeLeaf = NavTreeLeaf> {
  id?: string
  label: string
  title: string
  nodes: NavTreeNode<L>[]
  homeTo: string
  open: boolean
  groups: Record<string, boolean>
  onToggleGroup: (key: string) => void
  onNavigate: () => void
  onFold: () => void
}
```

- [ ] **Step 1: Write the failing test**

`packages/core/src/shell/NavTree.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Settings } from 'lucide-react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '@wasichai/testing'
import { NavTree, type NavTreeProps } from './NavTree'
import type { NavTreeNode } from './navTreeNodes'

// srtm-ui's ArbolNav, generic: groups of leaves or subgroups, the current leaf marked, the panel folded by its caller
const NODES: NavTreeNode[] = [
  {
    label: 'Contribuyentes',
    children: [
      { label: 'Buscar contribuyentes', to: '/contribuyentes' },
      { label: 'Nuevo contribuyente', to: '/contribuyentes/nuevo' }
    ]
  },
  {
    label: 'Tributos',
    children: [
      { label: 'Impuesto predial', children: [{ label: 'Cuenta corriente', to: '/cuenta' }] },
      { label: 'Arbitrios', to: '/arbitrios' }
    ]
  },
  { label: 'Administración', to: '/admin', external: true, icon: Settings }
]

function draw(props: Partial<NavTreeProps> = {}, options: { route?: string; language?: string } = {}) {
  const handlers = { onToggleGroup: vi.fn(), onNavigate: vi.fn(), onFold: vi.fn() }
  const result = renderWithProviders(
    <NavTree id="sidebar" label="Secciones" title="Mis trámites" nodes={NODES} homeTo="/" open groups={{}} {...handlers} {...props} />,
    options
  )
  return { ...result, ...handlers }
}

const nav = () => screen.getByRole('navigation', { name: 'Secciones' })
const current = () => Array.from(nav().querySelectorAll('[aria-current="page"]')).map((element) => element.textContent)

// a click on a plain link would make jsdom navigate (not implemented): keep the page, let react see the click
const stay = (event: Event) => event.preventDefault()
beforeEach(() => document.addEventListener('click', stay))
afterEach(() => document.removeEventListener('click', stay))

describe('NavTree', () => {
  it('draws the panel, the way home and the button that folds it', () => {
    draw()
    expect(nav()).toHaveAttribute('id', 'sidebar')
    expect(nav()).toHaveAttribute('data-slot', 'nav-tree')
    expect(nav()).toHaveClass('w-73', 'bg-table-head', 'border-r', 'border-border')
    expect(within(nav()).getByText('Mis trámites')).toHaveClass('text-lg', 'font-bold', 'text-link')
    const home = within(nav()).getByRole('link', { name: 'Ir al inicio' })
    expect(home).toHaveAttribute('href', '/')
    // home is not a leaf, but the header marks it on its own page
    expect(current()).toEqual(['Ir al inicio'])
    expect(within(nav()).getByRole('button', { name: 'Ocultar el menú' })).toHaveAttribute('aria-controls', 'sidebar')
  })

  it('goes home to the route the caller gives', () => {
    draw({ homeTo: '/inicio' })
    expect(within(nav()).getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/inicio')
  })

  it('draws every group open, a subgroup indented, its leaves deeper', () => {
    draw()
    const group = within(nav()).getByRole('button', { name: 'Contribuyentes' })
    expect(group).toHaveAttribute('aria-expanded', 'true')
    expect(group).toHaveAttribute('data-slot', 'nav-tree-group')
    expect(group).toHaveClass('text-[17px]', 'font-bold', 'text-ink')
    expect(document.getElementById(group.getAttribute('aria-controls')!)).toContainElement(
      within(nav()).getByRole('link', { name: 'Buscar contribuyentes' })
    )
    const sub = within(nav()).getByRole('button', { name: 'Impuesto predial' })
    expect(sub).toHaveClass('text-base', 'pl-[26px]')
    const leaf = within(nav()).getByRole('link', { name: 'Buscar contribuyentes' })
    expect(leaf).toHaveAttribute('data-slot', 'nav-tree-leaf')
    expect(leaf).toHaveClass('text-[15px]', 'text-link', 'pl-[34px]')
    expect(within(nav()).getByRole('link', { name: 'Cuenta corriente' })).toHaveClass('pl-[48px]')
    expect(
      within(nav())
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')])
    ).toEqual([
      ['Ir al inicio', '/'],
      ['Buscar contribuyentes', '/contribuyentes'],
      ['Nuevo contribuyente', '/contribuyentes/nuevo'],
      ['Cuenta corriente', '/cuenta'],
      ['Arbitrios', '/arbitrios'],
      ['Administración', '/admin']
    ])
  })

  // the caller keeps which groups are folded, by the labels from the root: two groups of one name stay apart
  it('folds the groups its caller keeps closed and asks to toggle one by its key', async () => {
    const { onToggleGroup } = draw({ groups: { Contribuyentes: false, 'Tributos/Impuesto predial': false } })
    const group = within(nav()).getByRole('button', { name: 'Contribuyentes' })
    const caret = group.querySelector('[data-slot="nav-tree-caret"]')
    expect(group).toHaveAttribute('aria-expanded', 'false')
    expect(caret).not.toHaveClass('rotate-90')
    expect(caret).toHaveClass('transition-transform', 'motion-reduce:transition-none')
    expect(within(nav()).queryByRole('link', { name: 'Buscar contribuyentes' })).not.toBeInTheDocument()
    expect(within(nav()).queryByRole('link', { name: 'Cuenta corriente' })).not.toBeInTheDocument()
    expect(within(nav()).getByRole('button', { name: 'Tributos' })).toHaveAttribute('aria-expanded', 'true')
    expect(within(nav()).getByRole('button', { name: 'Tributos' }).querySelector('[data-slot="nav-tree-caret"]')).toHaveClass('rotate-90')

    await userEvent.click(group)
    expect(onToggleGroup).toHaveBeenLastCalledWith('Contribuyentes')
    await userEvent.click(within(nav()).getByRole('button', { name: 'Impuesto predial' }))
    expect(onToggleGroup).toHaveBeenLastCalledWith('Tributos/Impuesto predial')
    within(nav()).getByRole('button', { name: 'Tributos' }).focus()
    await userEvent.keyboard('{Enter}')
    expect(onToggleGroup).toHaveBeenLastCalledWith('Tributos')
  })

  it('marks the current leaf, in bold and with a chevron', () => {
    draw({}, { route: '/contribuyentes/123' })
    expect(current()).toEqual(['Buscar contribuyentes'])
    const leaf = within(nav()).getByRole('link', { name: 'Buscar contribuyentes' })
    expect(leaf).toHaveClass('border-link', 'font-bold')
    expect(leaf.querySelector('svg')).not.toBeNull()
    const other = within(nav()).getByRole('link', { name: 'Arbitrios' })
    expect(other).toHaveClass('border-transparent')
    expect(other.querySelector('svg')).toBeNull()
  })

  it('draws an external leaf at the root like a group, with its icon, never current', async () => {
    const { onNavigate } = draw({}, { route: '/admin' })
    const admin = within(nav()).getByRole('link', { name: 'Administración' })
    expect(admin).toHaveAttribute('href', '/admin')
    expect(admin).toHaveAttribute('data-slot', 'nav-tree-group')
    expect(admin.querySelector('[data-slot="nav-tree-caret"] svg')).not.toBeNull()
    expect(current()).toEqual([])
    // another app loads in full: the panel has nothing to fold
    await userEvent.click(admin)
    expect(onNavigate).not.toHaveBeenCalled()
  })

  it('tells its caller of a pick and of a fold', async () => {
    const { onNavigate, onFold } = draw()
    await userEvent.click(within(nav()).getByRole('link', { name: 'Arbitrios' }))
    expect(onNavigate).toHaveBeenCalledTimes(1)
    await userEvent.click(within(nav()).getByRole('link', { name: 'Ir al inicio' }))
    expect(onNavigate).toHaveBeenCalledTimes(2)
    await userEvent.click(within(nav()).getByRole('button', { name: 'Ocultar el menú' }))
    expect(onFold).toHaveBeenCalledTimes(1)
  })

  it('is hidden while folded', () => {
    const { container } = draw({ open: false })
    expect(screen.queryByRole('navigation', { name: 'Secciones' })).not.toBeInTheDocument()
    expect(container.querySelector('[data-slot="nav-tree"]')).toHaveAttribute('hidden')
  })

  it('speaks English when the app does', () => {
    draw({}, { language: 'en' })
    expect(within(nav()).getByRole('link', { name: 'Go to the home page' })).toBeInTheDocument()
    expect(within(nav()).getByRole('button', { name: 'Hide the menu' })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to see it fail**

Run: `yarn --cwd packages/core vitest run src/shell/NavTree.test.tsx`
Expected: FAIL, `Failed to resolve import "./NavTree"`.

- [ ] **Step 3: Write `NavTree`**

`packages/core/src/shell/NavTree.tsx` (srtm-ui's `ArbolNav` with English names, `data-slot` hooks and core's words):

```tsx
import { cn } from '@wasichai/ui'
import { ChevronLeft, ChevronRight, Home } from 'lucide-react'
import { useId, type ComponentProps } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, NavLink, useLocation } from 'react-router'
import { currentNavTreeLeaf, isNavTreeGroup, type NavTreeGroup, type NavTreeLeaf, type NavTreeNode } from './navTreeNodes'

export interface NavTreeProps<L extends NavTreeLeaf = NavTreeLeaf> {
  id?: string
  // the <nav>'s aria-label
  label: string
  // the bold title over the groups: "Mis trámites", "Ventanilla"
  title: string
  nodes: NavTreeNode<L>[]
  // where the way home goes: the library hardcodes no route
  homeTo: string
  // folded, the panel is hidden; whoever folded it shows a way back
  open: boolean
  // which groups are open, by key (a group's labels from the root, joined by "/"); a group not in it is open
  groups: Record<string, boolean>
  onToggleGroup: (key: string) => void
  onNavigate: () => void
  onFold: () => void
}

interface Context {
  groups: Record<string, boolean>
  onToggleGroup: (key: string) => void
  onNavigate: () => void
  current: NavTreeLeaf | undefined
}

// the portal-tributario prototype's tree menu, with tokens: a light panel headed by the way home and a button that
// folds it, a title, and groups (buttons with a caret, folding their leaves) of leaves in the link colour, the current
// one marked on its left, in bold and with a chevron. the data-slot hooks let a theme refine it (ui's nav.css)
export function NavTree<L extends NavTreeLeaf>({ id, label, title, nodes, homeTo, open, groups, onToggleGroup, onNavigate, onFold }: NavTreeProps<L>) {
  const { t } = useTranslation()
  const { pathname } = useLocation()
  const context: Context = { groups, onToggleGroup, onNavigate, current: currentNavTreeLeaf(nodes, pathname) }
  return (
    <nav
      id={id}
      aria-label={label}
      data-slot="nav-tree"
      hidden={!open}
      // on a phone, over the whole row: a pick folds it
      className="w-73 shrink-0 overflow-y-auto border-r border-border bg-table-head max-sm:w-full print:hidden"
    >
      <div className="flex items-center gap-2.5 border-b border-line px-4 pt-3.5 pb-3">
        <Home aria-hidden className="size-[17px] shrink-0 text-link" strokeWidth={1.9} />
        <NavLink to={homeTo} end onClick={onNavigate} className="min-w-0 flex-1 text-base text-link hover:underline">
          {t('common.goHome')}
        </NavLink>
        <button
          type="button"
          aria-label={t('common.hideMenu')}
          aria-controls={id}
          onClick={onFold}
          className="grid size-6 shrink-0 place-items-center rounded text-link hover:bg-ink/4"
        >
          <ChevronLeft aria-hidden className="size-[15px]" strokeWidth={2.6} />
        </button>
      </div>
      <p className="px-4 pt-4 pb-2.5 text-lg leading-tight font-bold text-link">{title}</p>
      <Nodes nodes={nodes} level={0} parent="" context={context} />
    </nav>
  )
}

function Nodes({
  nodes,
  level,
  parent,
  context,
  id,
  hidden
}: {
  nodes: NavTreeNode[]
  level: number
  parent: string
  context: Context
  id?: string
  hidden?: boolean
}) {
  return (
    <ul id={id} hidden={hidden} className={cn(level === 0 && 'pb-4')}>
      {nodes.map((node) =>
        isNavTreeGroup(node) ? (
          <Group key={node.label} group={node} level={level} groupKey={parent + node.label} context={context} />
        ) : (
          <li key={node.to}>
            <Leaf leaf={node} level={level} current={node === context.current} onNavigate={context.onNavigate} />
          </li>
        )
      )}
    </ul>
  )
}

// a group (17px) or a subgroup (16px, indented): a button that folds its list, the caret turned while it is open
function Group({ group, level, groupKey, context }: { group: NavTreeGroup; level: number; groupKey: string; context: Context }) {
  const list = useId()
  const open = context.groups[groupKey] !== false
  const sub = level > 0
  return (
    <li>
      <button
        type="button"
        data-slot="nav-tree-group"
        aria-expanded={open}
        aria-controls={list}
        onClick={() => context.onToggleGroup(groupKey)}
        className={cn(
          'flex w-full items-center gap-[9px] text-left font-bold text-ink hover:text-link focus-visible:-outline-offset-2',
          sub ? 'py-[9px] pr-3.5 pl-[26px] text-base' : 'px-3.5 py-2.5 text-[17px]'
        )}
      >
        <Caret open={open} sub={sub} />
        <span className="min-w-0 flex-1">{group.label}</span>
      </button>
      <Nodes id={list} hidden={!open} nodes={group.children} level={level + 1} parent={`${groupKey}/`} context={context} />
    </li>
  )
}

// the prototype's caret: a small triangle that turns to point down, still when the user asks for less motion
function Caret({ open, sub }: { open: boolean; sub: boolean }) {
  return (
    <span
      aria-hidden
      data-slot="nav-tree-caret"
      className={cn(
        'grid shrink-0 place-items-center text-ink-muted transition-transform duration-130 motion-reduce:transition-none',
        sub ? 'size-[13px]' : 'size-3.5',
        open && 'rotate-90'
      )}
    >
      <svg viewBox="0 0 24 24" fill="currentColor" className={sub ? 'size-[9px]' : 'size-2.5'}>
        <path d="M8 5l10 7-10 7z" />
      </svg>
    </span>
  )
}

// a leaf: 15px in the link colour, indented under its group (deeper under a subgroup). one at the root, beside the
// groups (the administration), takes their type, with its icon, if any, where they have the caret
function Leaf({ leaf, level, current, onNavigate }: { leaf: NavTreeLeaf; level: number; current: boolean; onNavigate: () => void }) {
  const common = { leaf, 'aria-current': current ? ('page' as const) : undefined, onClick: leaf.external ? undefined : onNavigate }
  if (level === 0) {
    const Icon = leaf.icon
    return (
      <Anchor
        {...common}
        data-slot="nav-tree-group"
        className="flex w-full items-center gap-[9px] px-3.5 py-2.5 text-[17px] font-bold text-ink hover:text-link focus-visible:-outline-offset-2"
      >
        <span aria-hidden data-slot="nav-tree-caret" className="grid size-3.5 shrink-0 place-items-center text-ink-muted">
          {Icon && <Icon className="size-3.5" />}
        </span>
        <span className="min-w-0 flex-1">{leaf.label}</span>
      </Anchor>
    )
  }
  return (
    <Anchor
      {...common}
      data-slot="nav-tree-leaf"
      className={cn(
        'flex items-center gap-2 border-l-4 py-[9px] pr-3.5 text-[15px] leading-[1.35] text-link focus-visible:-outline-offset-2',
        level > 1 ? 'pl-[48px]' : 'pl-[34px]',
        current ? 'border-link bg-ink/6 font-bold' : 'border-transparent hover:bg-ink/4'
      )}
    >
      <span className="min-w-0 flex-1">{leaf.label}</span>
      {current && <ChevronRight aria-hidden className="size-3.5 shrink-0 text-link" strokeWidth={3} />}
    </Anchor>
  )
}

// a page of the app goes through the router; another app (external) is a plain link, loaded in full. Link, not
// NavLink: its prefix match would mark Buscar and Nuevo contribuyente at once, so aria-current comes from the rule
function Anchor({ leaf, ...props }: { leaf: NavTreeLeaf } & Omit<ComponentProps<'a'>, 'href'>) {
  return leaf.external ? <a href={leaf.to} {...props} /> : <Link to={leaf.to} {...props} />
}
```

In `packages/core/src/index.ts`, after the `navTreeNodes` export of Task 3:

```ts
export { NavTree, type NavTreeProps } from './shell/NavTree'
```

- [ ] **Step 4: Run it to see it pass**

Run: `yarn --cwd packages/core vitest run src/shell src/boundaries.test.ts`
Expected: PASS (`NavTree.test.tsx` 9 tests, the rest green). If the external leaf's click logs "Not implemented:
navigation", the `stay` listener is missing.

- [ ] **Step 5: Format, check and commit**

```bash
yarn format
yarn --cwd packages/core lint
git add packages/core/src/shell/NavTree.tsx packages/core/src/shell/NavTree.test.tsx packages/core/src/index.ts
git commit -F - <<'EOF'
feat(core): NavTree, a foldable tree menu

srtm-ui's ArbolNav, which caja-ui uses too: a panel headed by the way home and a fold button, groups and subgroups
that fold, leaves in the link colour with the current one marked. The caller keeps the panel's and the groups' state;
the hooks are data-slot="nav-tree", "nav-tree-group", "nav-tree-leaf" and "nav-tree-caret".

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 5: the tree partial of the `portal-tributario` sheet

**Files:**
- Create: `packages/ui/src/themes/portal-tributario/nav.css`
- Modify: `packages/ui/src/themes/portal-tributario/index.css`
- Modify: `packages/ui/src/themes/portalTributario.test.ts`

**Interfaces:**
- Consumes: Task 4's hooks; Task 2's `PARTIALS` and `SLOTS`.

- [ ] **Step 1: Write the failing tests**

In `packages/ui/src/themes/portalTributario.test.ts`: `PARTIALS` gains `'nav.css'`; `SLOTS` gains, after
`'alert-dismiss'`, `'nav-tree'`, `'nav-tree-group'`, `'nav-tree-leaf'`, `'nav-tree-caret'`. Append:

```ts
describe('nav.css', () => {
  const { unlayered: css } = layers(read('nav.css'))
  const TREE = "[data-slot='nav-tree']"
  const part = (selector: string) => rule(css, `${TREE} ${selector}`)
  const CURRENT = "[data-slot='nav-tree-leaf'][aria-current='page']"
  const HOVER = "[data-slot='nav-tree-leaf']:hover"
  const current = part(CURRENT)
  const hover = part(HOVER)
  const group = part("[data-slot='nav-tree-group']:hover")
  const caret = part("[data-slot='nav-tree-caret']")

  it('only styles the tree', () => {
    const selectors = rules(css).flatMap((r) => r.selectors)
    expect(selectors.length).toBeGreaterThan(0)
    expect(selectors.filter((selector) => !selector.startsWith(`${TREE} `))).toEqual([])
  })

  it("pins the prototype's current leaf, hovers and carets", () => {
    expect(current.get('color')).toBe('#0d4d80')
    expect(current.get('background-color')).toBe('#e6e6e6')
    expect(hover.get('background-color')).toBe('#e9e9e9')
    expect(group.get('color')).toBe('#0d4d80')
    expect(caret.get('color')).toBe('#555555')
  })

  // as specific as the hover, and after it: the current leaf keeps its background under the pointer
  it('puts the current leaf after the hover', () => {
    const selectors = rules(css).flatMap((r) => r.selectors)
    expect(selectors.indexOf(`${TREE} ${CURRENT}`)).toBeGreaterThan(selectors.indexOf(`${TREE} ${HOVER}`))
  })

  // over the lateral (table-head): a hovered leaf, the current one and a hovered group at 4.5:1; the caret, a graphic
  // next to its group's name, at 3:1
  it('keeps AA over the backgrounds it paints', () => {
    expect(
      failing([
        ['link on a hovered leaf', 'var(--link)', hover.get('background-color')!],
        ['current leaf', current.get('color')!, current.get('background-color')!],
        ['hovered group', group.get('color')!, 'var(--table-head)']
      ])
    ).toEqual([])
    expect(failing([['caret', caret.get('color')!, 'var(--table-head)']], 3)).toEqual([])
  })
})
```

- [ ] **Step 2: Run them to see them fail**

Run: `yarn --cwd packages/ui vitest run src/themes/portalTributario.test.ts`
Expected: FAIL: `ENOENT … nav.css`.

- [ ] **Step 3: Write the partial and import it**

`packages/ui/src/themes/portal-tributario/nav.css`:

```css
/* the tree menu (NavTree of @wasichai/core), with what the prototype gives it and no token has. the component draws it
   with tokens (bg-table-head, text-link, bg-ink/6 and /4 for the current leaf and the hover, text-ink-muted carets)
   and marks its parts with data-slot; this pins the prototype's values. unlayered, so it beats the utilities. scoped
   to the theme, and never into a subtree pinned to another theme */
@scope ([data-theme='portal-tributario']) to ([data-theme]:not([data-theme='portal-tributario'])) {
    [data-slot='nav-tree'] [data-slot='nav-tree-caret'] {
        color: #555555;
    }

    [data-slot='nav-tree'] [data-slot='nav-tree-group']:hover {
        color: #0d4d80;
    }

    [data-slot='nav-tree'] [data-slot='nav-tree-leaf']:hover {
        background-color: #e9e9e9;
    }

    /* after the hover: the current leaf keeps its background under the pointer */
    [data-slot='nav-tree'] [data-slot='nav-tree-leaf'][aria-current='page'] {
        color: #0d4d80;
        background-color: #e6e6e6;
    }
}
```

In `packages/ui/src/themes/portal-tributario/index.css`, after `@import './alerts.css';`:

```css
@import './nav.css';
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `yarn --cwd packages/ui vitest run src/themes`
Expected: PASS (the contrasts are 7.04, 4.70, 7.85 and 6.66:1).

- [ ] **Step 5: Format, check and commit**

```bash
yarn format
yarn --cwd packages/ui lint
git add packages/ui/src/themes
git commit -F - <<'EOF'
feat(ui): portal-tributario paints NavTree with the prototype's greys

nav.css joins the sheet: the carets, the hovers and the current leaf, on NavTree's data-slot hooks, inside the theme's
@scope. The current leaf comes after the hover, so it keeps its background under the pointer.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 6: READMEs, the whole check, the PR and the pre-release

**Files:**
- Modify: `packages/ui/README.md`
- Modify: `packages/core/README.md`

- [ ] **Step 1: `packages/ui/README.md`**

Prettier ignores markdown in this repository (`.prettierignore`): align the new table rows by hand with the existing
columns, and keep lines within 160 columns.

1. The first paragraph's list of primitives starts `Primitives shared by every wasichai package: \`Alert\`, \`Button\`, …`.
2. After the `PdfDialog` bullet, add a paragraph:

```markdown
`Alert` is a message in one of four tones (`success`, `warning`, `danger`, `notice`), with an optional bold `title` and,
with `onDismiss`, a check that dismisses it. `danger` is a `role="alert"`, the others a `role="status"`. Light and dark
draw it as text in its tone's colour; `className` adds the place's box or margins. Its dismiss label is
`common.dismissAlert`.
```

3. In the hooks table, after the `PdfDialog` row:

```markdown
| `Alert`              | `alert`          | `data-tone` (`success`, `warning`, `danger`, `notice`)                                       |
|                      | `alert-text`     | the title and the text                                                                       |
|                      | `alert-dismiss`  | the dismiss button                                                                           |
```

   and under the table, a sentence: ``Core's `NavTree` carries `nav-tree`, `nav-tree-group`, `nav-tree-leaf` and
   `nav-tree-caret` (see `@wasichai/core`'s README).``
4. In "What the sheet sets, under the theme only", after the **Tabs** bullet:

```markdown
- **Alerts**: the prototype's boxes, `14px 18px` at 14.5px, a 1px border and 3px corners, per tone its soft background,
  its text and Bootstrap 3's border (`#d6e9c6`, `#faebcc`, `#ebccd1`, `#e8e0c4`), the dismiss check at the top right.
  Over a caller's box classes (`rounded-md bg-danger/10 px-3 py-2`), the sheet's box wins.
- **Tree menu** (`NavTree` of `@wasichai/core`): the prototype's greys, which no token has: carets `#555`, a hovered
  group `#0d4d80`, a hovered leaf on `#e9e9e9`, the current leaf `#0d4d80` on `#e6e6e6`.
```

- [ ] **Step 2: `packages/core/README.md`**

After the "Themes and preferences" section (before `## Backend modules are optional`), add:

````markdown
## Tree menu (`NavTree`)

A foldable tree menu for an app's own shell (srtm-ui's and caja-ui's portals): a panel headed by the way home and a
button that folds it, a bold title, groups and subgroups that fold, and leaves in the link colour, the current one
marked in bold with a chevron. A leaf at the root is drawn like a group, with its `icon` where a group has its caret;
an `external` leaf is a plain `<a>` to another app, never current.

```tsx
import { NavTree, type NavTreeNode } from '@wasichai/core'

const nodes: NavTreeNode[] = [
  { label: 'Contribuyentes', children: [{ label: 'Buscar contribuyentes', to: '/contribuyentes' }] },
  { label: 'Administración', to: '/admin', external: true, icon: Settings }
]

<NavTree id="sidebar" label="Secciones" title="Mis trámites" nodes={nodes} homeTo="/"
  open={open} groups={groups} onToggleGroup={toggle} onNavigate={foldOnPhone} onFold={() => setOpen(false)} />
```

The caller keeps the state: `open` hides the panel, and `groups` says which groups are folded, by their labels from the
root joined by `/` (`Tributos/Impuesto predial`); a group not in it is open. An app extends `NavTreeLeaf` with its own
fields, and the helpers keep that type: `navTreeLeaves(nodes)` lists the leaves, `currentNavTreeLeaf(nodes, pathname)`
gives the current one (its own route, else one whose `alsoAt` pattern matches, else the longest start of the path),
`isNavTreeGroup(node)` tells a group from a leaf. Its words are `common.goHome` and `common.hideMenu`. Its hooks
(`data-slot`): `nav-tree`, `nav-tree-group`, `nav-tree-leaf`, `nav-tree-caret`; `@wasichai/ui/themes/portal-tributario.css`
paints them.
````

- [ ] **Step 3: The whole check**

```bash
yarn format
yarn lint && yarn test && yarn build && yarn test:tooling
node tooling/check-release.mjs --pack 0.0.0-local
```

Expected: all green; `check-release` lists `dist/themes/portal-tributario/alerts.css` and `nav.css` in `@wasichai/ui`'s
pack. If the core build complains about `Alert` or `NavTree` types, fix before going on.

- [ ] **Step 4: Commit the READMEs**

```bash
git add packages/ui/README.md packages/core/README.md
git commit -F - <<'EOF'
docs: Alert and NavTree in the packages' READMEs

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

- [ ] **Step 5: Push and open the PR to `dev`**

```bash
git push -u origin feat/ui-componentes-tema
gh pr create --base dev --title "feat: Alert and NavTree go up from the portal-tributario theme" --body-file - <<'EOF'
Phase C of #12, for #14: caja-ui is the second user of srtm-ui's `Alerta` and `ArbolNav`.

- `@wasichai/ui`: `Alert` (four tones, title, dismiss), hooks `alert`, `alert-text`, `alert-dismiss` with `data-tone`.
- `@wasichai/core`: `NavTree` and its nodes (`NavTreeLeaf`, `NavTreeGroup`, `NavTreeNode`, `isNavTreeGroup`,
  `navTreeLeaves`, `currentNavTreeLeaf`), hooks `nav-tree*`. In core because it needs react-router.
- The `portal-tributario` sheet gains `alerts.css` and `nav.css`, inside its `@scope`.
- Core's bundle: `common.dismissAlert`, `common.goHome`, `common.hideMenu` (es, en).

Light and dark do not change. `BandaTitulo`, `PasosGalon`, `BarraInstruccion` and `useVarianteTema` stay in the apps
(no second user yet), so #14 stays open.

Spec: wasichai `docs/superpowers/specs/2026-10-06-theme-components-design.md`.

Refs #14

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

- [ ] **Step 6: After the merge, the pre-release (ask the user first: it publishes)**

```bash
git fetch origin --tags
git switch dev && git pull --ff-only
node tooling/dev-release.mjs next   # prints 0.5.0-dev.0
git tag v0.5.0-dev.0 && git push origin v0.5.0-dev.0
gh run watch "$(gh run list --workflow release-dev.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

Expected: the workflow publishes every package at `0.5.0-dev.0` under the dist-tag `dev` and creates the GitHub
prerelease (workflow "Release dev", `.github/workflows/release-dev.yml`, triggered by `v*-dev.*` tags).

---

### Task 7: the decision and the history, in wasichai's docs

**Files** (in `~/IdeaProjects/wasichai`, branch `docs/theme-components`):
- Modify: `docs/adr/0035-theme-extension-tokens-slots-and-optional-sheets.md`
- Modify: `docs/modules/core.md`
- Modify: `docs/HISTORY.md`

- [ ] **Step 1: ADR-035**

1. In the hooks table, after the `Tabs` row:

```markdown
| `Alert` | `data-slot="alert"` with `data-tone`, `"alert-text"`, `"alert-dismiss"` (since 0.5) |
| `NavTree` (`@wasichai/core`) | `data-slot="nav-tree"`, `"nav-tree-group"`, `"nav-tree-leaf"`, `"nav-tree-caret"` (since 0.5) |
```

2. At the end of the paragraph **A theme with its own layout stays in the app.**, add:

```markdown
Updated on 2026-10-06: caja-ui, rewritten on the packages the way srtm-ui is, became the second user of `Alerta` and
`ArbolNav`. `Alert` went up to `@wasichai/ui` and `NavTree` to `@wasichai/core` (it needs `react-router`, which ui does
not have), with English names and their words in core's bundle; their partials joined the sheet on `data-slot`
(`alerts.css`, `nav.css`). `BandaTitulo`, `PasosGalon`, `BarraInstruccion`, the shells per theme and `useVarianteTema`
stay in the apps
([the spec](../superpowers/specs/2026-10-06-theme-components-design.md)).
```

- [ ] **Step 2: `docs/modules/core.md`, "Frontend package"**

1. After the sentence that ends `…are its parts, usable on their own.`, add: ``` `NavTree` draws an app's foldable
   tree menu (groups, subgroups, leaves, the current one marked); `navTreeLeaves`, `currentNavTreeLeaf` and
   `isNavTreeGroup` read its nodes.```
2. The `@wasichai/ui` paragraph's list starts `(\`Alert\`, \`Button\`, …`.

- [ ] **Step 3: `docs/HISTORY.md`**

Under `# Change history` and its first paragraph, before the 2026-09-29 entry:

```markdown
## 2026-10-06 — Theme components with a second user go up to wasichai-ui

Two components srtm-ui wrote for its `portal-tributario` theme move into wasichai-ui, because caja-ui, which copied
them, is their second user (wasichai-ui rule 6): `Alert` in `@wasichai/ui`, a message in four tones (`success`,
`warning`, `danger`, `notice`) with a bold title and a dismiss check, and `NavTree` in `@wasichai/core`, the foldable
tree menu, with its nodes and the rule for the current leaf (its own route, then `alsoAt`, then the longest start of
the path). `NavTree` lives in core because it needs `react-router`. Their names are English and their words are in
core's bundle (`common.dismissAlert`, `common.goHome`, `common.hideMenu`); their hooks are `data-slot`s (`alert` with
`data-tone`, `nav-tree*`), and the `portal-tributario` sheet paints them with two new partials, `alerts.css` and
`nav.css`. Light and dark do not change. They ship in the pre-release `0.5.0-dev.0`; srtm-ui and caja-ui adopt it and
delete their copies, and caja-ui's alerts gain the prototype's box under the theme, which it never had copied.
`BandaTitulo`, `PasosGalon`, `BarraInstruccion` and `useVarianteTema` stay in the apps
([wasichai/wasichai-ui#14](https://github.com/wasichai/wasichai-ui/issues/14) stays open for them). Design and plans:
[the spec](superpowers/specs/2026-10-06-theme-components-design.md),
[wasichai-ui](superpowers/plans/2026-10-06-theme-components-wasichai-ui.md),
[srtm-ui](superpowers/plans/2026-10-06-theme-components-srtm-ui.md) and
[caja-ui](superpowers/plans/2026-10-06-theme-components-caja-ui.md).
```

- [ ] **Step 4: Check the lines and commit**

```bash
cd ~/IdeaProjects/wasichai
awk 'length > 160 {print FILENAME": "FNR}' docs/adr/0035-*.md docs/modules/core.md docs/HISTORY.md docs/superpowers/plans/2026-10-06-*.md
git add docs
git commit -F - <<'EOF'
docs: Alert and NavTree go up to wasichai-ui (ADR-035 note, history)

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

Expected: `awk` prints nothing.

- [ ] **Step 5: Push and open the PR to `dev`**

```bash
git push -u origin docs/theme-components
gh pr create --base dev --title "docs: Alert and NavTree go up to wasichai-ui" --body-file - <<'EOF'
The design, the three plans, a dated note on ADR-035, core's module doc and the history entry for
wasichai/wasichai-ui#14 (phase C of #12): caja-ui is the second user of srtm-ui's `Alerta` and `ArbolNav`.

Refs wasichai/wasichai-ui#14

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```
