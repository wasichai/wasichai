# Theme components, srtm-ui: adopt `Alert` and `NavTree` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** srtm-ui draws its alerts with `Alert` of `@wasichai/ui` and its tree menu with `NavTree` of `@wasichai/core`,
and deletes `Alerta`, `ArbolNav` and their partials `alerts.css` and `nav.css`.

**Architecture:** a codemod renames every `<Alerta>` (tag, props, tones, import). `navTree.ts` keeps the trámites and
`arbolPara`, on core's node types; core's `currentNavTreeLeaf` replaces `hojaActiva`; `LateralPortal` draws `NavTree`.
The look under `portal-tributario` stays the same: the library's sheet carries the same values on `data-slot`.

**Tech Stack:** React 19, TypeScript, Vite 8, vitest + testing-library, `@wasichai/*` `0.5.0-dev.0`.

**Spec:** wasichai [`docs/superpowers/specs/2026-10-06-theme-components-design.md`](../specs/2026-10-06-theme-components-design.md).
It depends on the wasichai-ui plan
([`2026-10-06-theme-components-wasichai-ui.md`](2026-10-06-theme-components-wasichai-ui.md)): its Task 6 builds the
packages this plan develops against, and publishes `0.5.0-dev.0`, which Task 4 here needs.

**Repository:** `~/IdeaProjects/srtm-ui`, branch `feat/alert-navtree-de-wasichai` off `origin/dev`; PR to `dev`.

## Global Constraints

- App identifiers stay Spanish (`HojaNav`, `arbolPara`); comments in English, caveman style.
- `.editorconfig`: 2 spaces in TS/TSX, max 160 columns. `yarn format` before each commit (prettier also formats
  `src/**/*.md`).
- Light, dark and `portal-tributario` look the same as before: only the hooks' names change (`data-ui="alerta"` →
  `data-slot="alert"`, `data-tono` → `data-tone`, `arbol-*` → `nav-tree*`).
- Tones: `exito` → `success`, `atencion` → `warning`, `error` → `danger`, `aviso` → `notice`; props `tono` → `tone`,
  `titulo` → `title`, `onCerrar` → `onDismiss`.
- Tree fields: `hijos` → `children`, `tambienEn` → `alsoAt`, `externa` → `external`, `icono` → `icon`.
- Conventional Commits (header ≤ 120). Every commit message ends with:

  ```
  Refs wasichai/wasichai-ui#14

  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
  ```

- The overlay of wasichai-ui's local build in `node_modules` (Task 1) is never committed; Task 4 replaces it with the
  published `0.5.0-dev.0`.

## Review Focus

- An alert with a ternary tone (`tono={d.en_plazo ? 'exito' : 'atencion'}` in `DescargosYResoluciones.tsx`): both
  branches are renamed, and a wrong one fails `tsc` (Task 1, Step 5).
- A dialog's own `onCerrar` (`DialogoDeActo`, `DialogoVersion`…) is not an alert's: it must stay `onCerrar` (Task 1,
  the codemod only touches `<Alerta …>` tags; Step 5 greps for it).
- `/infracciones/cuis` with `Expedientes`' `alsoAt: ['/infracciones/:id']`: CUIS stays current (Task 2, the
  `NAV_TREE` table).
- The administration leaf for a non-admin: `arbolPara` still drops it, now reading `soloAdmin` on the leaf (Task 2).
- A stored `srtm.nav` from before: its keys are the groups' labels, which do not change, so the folded groups survive
  (Task 2, the memory test stays as is).

---

### Task 1: the alerts are `Alert`

**Files:**
- Delete: `src/portal/components/Alerta.tsx`, `src/portal/components/Alerta.test.tsx`,
  `src/themes/portal-tributario/alerts.css`
- Modify: `src/themes/portal-tributario/index.css`, `src/themes/parciales.test.tsx`
- Modify (codemod): the 21 files that draw an `Alerta`: `KitDelPortal.tsx`, `components/DialogoDeActo.tsx` and, in
  `pages/`, `CuisPage`, `EmisionesPage`, `Arbitrios`, `AnunciosPages`, `DescargosYResoluciones`, `ArbitriosPages`,
  `ExpedientePage`, `NuevaDeclaracionPage`, `DeterminarArbitrios`, `NotificacionesPage`, `DeclaracionPage`,
  `TitularesDelPredio`, `EscalasYPlazosPage`, `NuevaActaPage`, `AnuncioPage`, `NuevoAnuncioPage`,
  `DeterminacionesPage`, `BuscarPrediosDialog`, `ContribuyenteListas`
- Modify: `src/portal/KitDelPortal.test.tsx:29-30`, `src/portal/anuncios.test.tsx:344-347`

**Interfaces:**
- Consumes: `Alert` from `@wasichai/ui` (`tone`, `title?`, `children`, `onDismiss?`, `className?`), hooks
  `data-slot="alert"` + `data-tone`.

- [ ] **Step 1: Branch, and put wasichai-ui's local build under `node_modules`**

wasichai-ui's branch `feat/ui-componentes-tema` must be built (`yarn build` there, its plan's Task 6, Step 3).

```bash
cd ~/IdeaProjects/srtm-ui
git fetch origin
git switch -c feat/alert-navtree-de-wasichai origin/dev
yarn install
for p in core ui; do rsync -a --delete ../wasichai-ui/packages/$p/dist/ node_modules/@wasichai/$p/dist/; done
grep -c 'alert-dismiss' node_modules/@wasichai/ui/dist/index.js
grep -c 'nav-tree-caret' node_modules/@wasichai/core/dist/index.js
```

Expected: both greps print a number above 0 (the overlay is in place). `yarn test` now passes as before: nothing
uses the new exports yet.

- [ ] **Step 2: Turn the tests to the new hooks (they fail)**

`src/portal/KitDelPortal.test.tsx`, lines 29–30:

```tsx
    expect(alerta).toHaveAttribute('data-slot', 'alert')
    expect(alerta).toHaveAttribute('data-tone', 'danger')
```

`src/portal/anuncios.test.tsx`, lines 344–347: `closest('[data-ui="alerta"]')` becomes `closest('[data-slot="alert"]')`
(twice), and `toHaveAttribute('data-tono', 'atencion')` becomes `toHaveAttribute('data-tone', 'warning')`.

Run: `yarn vitest run src/portal/KitDelPortal.test.tsx src/portal/anuncios.test.tsx`
Expected: FAIL: the alerts still carry `data-ui="alerta"`.

- [ ] **Step 3: Delete `Alerta` and run the codemod**

Save this outside the repository, as `$SCRATCH/alerta-a-alert.mjs` (`SCRATCH` is the session's scratchpad, or
`SCRATCH=$(mktemp -d)`):

```js
// Alerta (srtm-ui, caja-ui) -> Alert (@wasichai/ui): each <Alerta …> tag with its props and tones, </Alerta>, and the
// import. only inside the tags: a dialog's own onCerrar is not an alert's
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const TONES = { exito: 'success', atencion: 'warning', error: 'danger', aviso: 'notice' }
const PROPS = { tono: 'tone', titulo: 'title', onCerrar: 'onDismiss' }
const IMPORT = /^import \{ Alerta \} from '[^']*\/Alerta'\n/m
const UI = /import \{([^}]*)\} from '@wasichai\/ui'/

const files = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? files(path) : name.endsWith('.tsx') ? [path] : []
  })

// where the opening tag that starts at `from` ends: the first '>' outside braces and quotes
function tagEnd(source, from) {
  let depth = 0
  let quote = null
  for (let i = from; i < source.length; i++) {
    const c = source[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
    } else if (c === '"' || c === "'" || c === '`') quote = c
    else if (c === '{') depth++
    else if (c === '}') depth--
    else if (c === '>' && depth === 0) return i
  }
  throw new Error(`unclosed <Alerta at ${from}`)
}

const convert = (tag) =>
  tag
    .replace(/^<Alerta/, '<Alert')
    .replace(/\b(tono|titulo|onCerrar)=/g, (_, prop) => `${PROPS[prop]}=`)
    .replace(/(['"])(exito|atencion|error|aviso)\1/g, (_, quote, tone) => `${quote}${TONES[tone]}${quote}`)

const byName = (a, b) => a.replace(/^type /, '').localeCompare(b.replace(/^type /, ''), 'en', { sensitivity: 'base' })

for (const file of files(process.argv[2])) {
  const source = readFileSync(file, 'utf8')
  if (!IMPORT.test(source)) continue
  let out = ''
  let last = 0
  for (let at = source.indexOf('<Alerta'); at >= 0; at = source.indexOf('<Alerta', at + 1)) {
    if (at < last || !/[\s>/]/.test(source[at + 7])) continue
    const end = tagEnd(source, at)
    out += source.slice(last, at) + convert(source.slice(at, end + 1))
    last = end + 1
  }
  out = (out + source.slice(last)).replaceAll('</Alerta>', '</Alert>')
  const ui = UI.exec(out)
  if (ui) {
    const names = [...ui[1].split(',').map((name) => name.trim()).filter(Boolean), 'Alert'].sort(byName)
    out = out.replace(IMPORT, '').replace(UI, `import { ${names.join(', ')} } from '@wasichai/ui'`)
  } else out = out.replace(IMPORT, "import { Alert } from '@wasichai/ui'\n")
  writeFileSync(file, out)
  console.log(file)
}
```

```bash
git rm -q src/portal/components/Alerta.tsx src/portal/components/Alerta.test.tsx
node "$SCRATCH/alerta-a-alert.mjs" src
```

Expected: it prints the 21 files of the list above.

- [ ] **Step 4: Delete `alerts.css` and its tests**

```bash
git rm -q src/themes/portal-tributario/alerts.css
```

- `src/themes/portal-tributario/index.css`: remove `@import './alerts.css';`.
- `src/themes/parciales.test.tsx`: `PARCIALES` loses `'alerts.css'`; delete the whole `describe('alerts.css', …)`.

- [ ] **Step 5: Check what is left by hand**

```bash
grep -rnw "Alerta" src --include='*.ts' --include='*.tsx'
grep -rn "tono=\|titulo=" src --include='*.tsx' | grep "<Alert\b"
grep -rn "onDismiss" src --include='*.tsx'
```

Expected: the first prints only comments (e.g. `EscalasYPlazosPage.tsx:219`, "named in an Alerta"): change them to
`Alert`. The second and third print nothing: no alert used `onCerrar`, and every dialog keeps its own.

- [ ] **Step 6: Run the checks**

```bash
yarn format
yarn typecheck && yarn test && yarn lint
```

Expected: PASS, the two tests of Step 2 included. Under `portal-tributario` the boxes come from the library's
`alerts.css`, with srtm's values.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -F - <<'EOF'
refactor(portal): las alertas son el Alert de @wasichai/ui

Alerta y alerts.css subieron a la librería (Alert y su parcial en la hoja del tema), con caja-ui como segundo usuario.
Los tonos pasan a success, warning, danger y notice, y los ganchos a data-slot="alert" y data-tone. Ni light ni dark ni
portal-tributario cambian.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 2: the tree menu is `NavTree`

**Files:**
- Delete: `src/portal/shell/ArbolNav.tsx`, `src/themes/portal-tributario/nav.css`
- Modify: `src/portal/shell/navTree.ts`, `src/portal/shell/LateralPortal.tsx`,
  `src/themes/portal-tributario/index.css`, `src/themes/parciales.test.tsx`, `src/portal/arbolNav.test.tsx`

**Interfaces:**
- Consumes: from `@wasichai/core`, `NavTree` (props `id`, `label`, `title`, `nodes`, `homeTo`, `open`, `groups`,
  `onToggleGroup`, `onNavigate`, `onFold`), `currentNavTreeLeaf`, `isNavTreeGroup`, types `NavTreeLeaf`,
  `NavTreeGroup<L>`, `NavTreeNode<L>`.
- Produces: `HojaNav extends NavTreeLeaf { soloAdmin? }`, `GrupoNav = NavTreeGroup<HojaNav>`,
  `NodoNav = NavTreeNode<HojaNav>`, `NAV_TREE`, `arbolPara(nodos, { isAdmin })`.

- [ ] **Step 1: Turn the tests to core's tree (they fail)**

In `src/portal/arbolNav.test.tsx`:

1. Imports: drop `import { ArbolNav } from './shell/ArbolNav'`; `import { arbolPara, hojaActiva, NAV_TREE, type NodoNav } from './shell/navTree'`
   becomes `import { arbolPara, NAV_TREE, type NodoNav } from './shell/navTree'`; add
   `import { currentNavTreeLeaf } from '@wasichai/core'`. `MemoryRouter` is no longer used: remove its import.
2. In `describe('NAV_TREE')`, the `ver` helper reads `children`:

```tsx
    const ver = (nodos: NodoNav[]): unknown => nodos.map((nodo) => ('children' in nodo ? [nodo.label, ver(nodo.children)] : `${nodo.label} ${nodo.to}`))
```

   and the table's assertion becomes `expect(currentNavTreeLeaf(NAV_TREE, path)?.label).toBe(label)`.
3. Delete `describe('ArbolNav', …)`: subgroups are core's `NavTree.test.tsx` now.
4. `expect(nav).toHaveAttribute('data-ui', 'arbol-nav')` becomes `expect(nav).toHaveAttribute('data-slot', 'nav-tree')`,
   and `grupo.querySelector('[data-ui="arbol-caret"]')!` becomes `grupo.querySelector('[data-slot="nav-tree-caret"]')!`.

In `src/themes/parciales.test.tsx`: `PARCIALES` loses `'nav.css'`; delete the whole `describe('nav.css', …)`.

Run: `yarn vitest run src/portal/arbolNav.test.tsx`
Expected: FAIL: `currentNavTreeLeaf` cannot read `hijos`, and the panel still carries `data-ui="arbol-nav"`.

- [ ] **Step 2: `navTree.ts` on core's nodes**

Rename the fields of `NAV_TREE` in place:

```bash
# macOS sed: no \b. these names only appear as fields of NAV_TREE (the old types are replaced below)
sed -i '' -e 's/hijos: \[/children: [/g' -e 's/tambienEn: /alsoAt: /g' -e 's/externa: /external: /g' -e 's/icono: /icon: /g' src/portal/shell/navTree.ts
```

Then replace everything above `export const NAV_TREE` with:

```ts
import { isNavTreeGroup, type NavTreeGroup, type NavTreeLeaf, type NavTreeNode } from '@wasichai/core'
import { Settings } from 'lucide-react'

// the tree menu of the portal (portal-tributario theme): what a clerk does, grouped by what it is done on. the home
// page is not a leaf, the panel's header takes there. the tree's shape, its drawing and its current leaf are
// @wasichai/core's (NavTree); the trámites and who sees them are srtm's

export interface HojaNav extends NavTreeLeaf {
  // for admins only: the administration. no group has it
  soloAdmin?: boolean
}

export type GrupoNav = NavTreeGroup<HojaNav>
export type NodoNav = NavTreeNode<HojaNav>
```

and everything below the closing `]` of `NAV_TREE` with:

```ts
// the tree a user sees: what is for admins only, only for them; a group left empty goes too
export function arbolPara(nodos: NodoNav[], { isAdmin }: { isAdmin: boolean }): NodoNav[] {
  return nodos.flatMap((nodo): NodoNav[] => {
    if (!isNavTreeGroup(nodo)) return nodo.soloAdmin && !isAdmin ? [] : [nodo]
    const children = arbolPara(nodo.children, { isAdmin })
    return children.length ? [{ ...nodo, children }] : []
  })
}
```

The comments inside `NAV_TREE` that say `tambienEn` say `alsoAt`.

- [ ] **Step 3: `LateralPortal` draws `NavTree`; delete `ArbolNav` and `nav.css`**

`src/portal/shell/LateralPortal.tsx`:

```tsx
import { NavTree } from '@wasichai/core'
import { useMemo, useState } from 'react'
import { useSession } from '../auth/session'
import type { LateralProps } from './comun'
import { arbolPara, NAV_TREE } from './navTree'
import { guardarNav, leerNav } from './panelLateral'

// the portal's lateral: the tree of trámites (NAV_TREE, the administration for admins only) in core's NavTree, its
// groups remembered for the browser tab like the panel (usePanelLateral)
export function LateralPortal({ abierto, onNavegar, onPlegar }: LateralProps) {
  const { isAdmin } = useSession()
  const nodos = useMemo(() => arbolPara(NAV_TREE, { isAdmin }), [isAdmin])
  const [grupos, setGrupos] = useState(() => leerNav().grupos ?? {})

  const alternar = (clave: string) => {
    const siguientes = { ...grupos, [clave]: grupos[clave] === false }
    setGrupos(siguientes)
    guardarNav({ grupos: siguientes })
  }

  return (
    <NavTree
      id="sidebar"
      label="Secciones"
      title="Mis trámites"
      nodes={nodos}
      homeTo="/"
      open={abierto}
      groups={grupos}
      onToggleGroup={alternar}
      onNavigate={onNavegar}
      onFold={onPlegar}
    />
  )
}
```

```bash
git rm -q src/portal/shell/ArbolNav.tsx src/themes/portal-tributario/nav.css
```

`src/themes/portal-tributario/index.css`: remove `@import './nav.css';`.

- [ ] **Step 4: Run the checks**

```bash
yarn format
yarn typecheck && yarn test && yarn lint
grep -rn "ArbolNav\|hojaActiva\|esGrupo\|tambienEn\|arbol-\(nav\|grupo\|hoja\|caret\)" src --include='*.ts' --include='*.tsx'
```

Expected: PASS, `arbolNav.test.tsx` included ("Ir al inicio" and "Ocultar el menú" now come from core's bundle). The
grep prints nothing.

- [ ] **Step 5: Commit**

```bash
git add -A src
git commit -F - <<'EOF'
refactor(portal): el menú en árbol es el NavTree de @wasichai/core

ArbolNav, hojaActiva y nav.css subieron a la librería (NavTree, currentNavTreeLeaf y su parcial en la hoja del tema),
con caja-ui como segundo usuario. NAV_TREE y arbolPara se quedan, sobre los nodos de core (children, alsoAt, external,
icon). El árbol se ve igual en todos los temas.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 3: the READMEs say where the pieces are

**Files:**
- Modify: `src/themes/portal-tributario/README.md`
- Modify: `src/kit/README.md`

- [ ] **Step 1: `src/themes/portal-tributario/README.md`**

1. Layer 2: "Los controles, las tablas y las pestañas de la librería los pinta la hoja de `@wasichai/ui`" becomes "Los
   controles, las tablas, las pestañas, las alertas y el menú en árbol de la librería los pinta la hoja de
   `@wasichai/ui`", and "(`tables.css`, `tabs.css`, `alerts.css`…)" becomes "(`tables.css`, `tabs.css`, `pasos.css`…)".
2. Files table: delete the four rows of `alerts.css`, `Alerta.tsx`, `nav.css` and `ArbolNav.tsx`; the row of
   `@wasichai/ui/themes/portal-tributario.css` says "…los controles, tablas, pestañas, alertas y menú en árbol de la
   librería sobre sus `data-slot`".
3. Replace the section `### Alertas (\`alerts.css\`, #50)` with:

```markdown
### Alertas (#50, de la librería desde `@wasichai/*` 0.5)

Las alertas son `Alert` de `@wasichai/ui`: `tone` (`'success' | 'warning' | 'danger' | 'notice'`), `title?` (en negrita
al inicio: "Atención.", "Sr. contribuyente,"), `children`, `onDismiss?` (un check con `aria-label` "Entendido, cerrar
el aviso", del bundle de core) y `className`. `role="alert"` para `danger` y `role="status"` para el resto. Pone
`data-slot="alert"` y `data-tone`, y envuelve el texto en `data-slot="alert-text"`.

- **light y dark no cambian.** Fuera del tema, `Alert` es el texto en el color de su tono más lo que cada sitio le pase
  en `className`. Así, el error de `RecordForm` conserva su caja (`rounded-md bg-danger/10 px-3 py-2`).
- **Bajo el tema**, el `alerts.css` de la hoja de la librería pinta la caja del prototipo, con los valores que tenía
  aquí (#50): `padding: 14px 18px`, 14.5px, `line-height: 1.6`, radio 3px, y por tono el fondo, el texto y el borde de
  Bootstrap 3 (`#D6E9C6`, `#FAEBCC`, `#EBCCD1`, `#E8E0C4`).
- El toast queda fuera de alcance: el portal no tiene toasts.
```

4. In `### Menú en árbol (#53)`: the first paragraph says "…el árbol de trámites del prototipo: `NavTree` de
   `@wasichai/core` (desde `@wasichai/*` 0.5), un componente genérico que recibe el árbol, con el contenido declarado
   en `NAV_TREE` (`src/portal/shell/navTree.ts`)…"; in the leaves bullet, `tambienEn` becomes `alsoAt`, and it says the
   current leaf is core's `currentNavTreeLeaf` (su ruta, luego `alsoAt`, luego el inicio más largo). Replace the
   paragraph "`nav.css` fija lo que…" and its table with:

```markdown
Los grises del prototipo (hoja activa `#0D4D80` sobre `#E6E6E6`, hover `#E9E9E9`, grupo `#0D4D80`, caret `#555555`)
los fija el `nav.css` de la hoja de la librería sobre `data-slot="nav-tree*"`, con los mismos valores y contrastes que
tenía aquí.
```

   and the tests line says: "Tests: `src/portal/arbolNav.test.tsx` (`NAV_TREE`, hoja activa, plegado, memoria, pantalla
   estrecha, clásico intacto); el componente y su parcial los prueba wasichai-ui."
5. In the structure table, the tree row's component cell: "`NavTree` (`@wasichai/core`), `navTree.ts`,
   `LateralPortal.tsx`".
6. Hooks table: the `Alertas` row becomes "`data-slot`: `alert` (+ `data-tone`), `alert-text`, `alert-dismiss` |
   `Alert` (`@wasichai/ui`)"; the `Estructura portal` row loses `arbol-nav`, `arbol-grupo`, `arbol-hoja`, `arbol-caret`
   and `ArbolNav`, and gains "`data-slot`: `nav-tree`, `nav-tree-group`, `nav-tree-leaf`, `nav-tree-caret`" and
   "`NavTree` (`@wasichai/core`)". The paragraph above the table adds: "`alert*` y `nav-tree*`, desde 0.5".
7. Section `## wasichai-ui`: after the bullet of the light green, add
   "- `Alert` en `@wasichai/ui` y `NavTree` en `@wasichai/core`, con sus parciales en la hoja del tema
   (wasichai/wasichai-ui#14, 0.5.0-dev.0): caja-ui fue el segundo usuario." The paragraph "Pendiente, en
   wasichai/wasichai-ui#14: …" becomes "Pendiente, en wasichai/wasichai-ui#14: `PasosGalon`, `BarraInstruccion`,
   `BandaTitulo` y la idea de `useVarianteTema`. Solo suben con un segundo usuario concreto (regla 6 del `CLAUDE.md` de
   wasichai-ui). Hasta entonces siguen aquí, con sus parciales."

- [ ] **Step 2: `src/kit/README.md`**

1. Line 41: "`renderAlert` (su `Alerta` con tono de error)" becomes "`renderAlert` (el `Alert` de `@wasichai/ui` con
   tono `danger`)".
2. The row "Estructura de portal: `Alerta`, `PasosGalon`, `BarraInstruccion`, `ArbolNav`, `BandaTitulo`": its state
   cell says "`Alerta` y `ArbolNav` ⬆ hechos (`Alert` y `NavTree`, 0.5.0-dev.0); el resto ⬆ fase 2 (wasichai-ui#14)".
3. The row "Estructura de portal (`Alerta`, `PasosGalon`, …)" of "Qué sube en la fase 2": the piece cell is
   "Estructura de portal (`PasosGalon`, `BarraInstruccion`, `BandaTitulo`)"; `Alerta` and `ArbolNav` are already up.

- [ ] **Step 3: Format and commit**

```bash
yarn format && yarn lint
git add src/themes/portal-tributario/README.md src/kit/README.md
git commit -F - <<'EOF'
docs(portal): Alert y NavTree vienen de @wasichai/*

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 4: `@wasichai/*` 0.5.0-dev.0 and the PR

Starts once wasichai-ui's `v0.5.0-dev.0` is published (its plan's Task 6, Step 6).

**Files:**
- Modify: `package.json`, `yarn.lock`

- [ ] **Step 1: Move every `@wasichai/*` to the pre-release**

In `package.json`, every `"@wasichai/…": "0.4.0"` (`core`, `documents`, `forms`, `gis`, `pages`, `ui`, `views`,
`workflow` and the dev dependency `testing`) becomes `"0.5.0-dev.0"`.

```bash
yarn install
grep -c '"version": "0.5.0-dev.0"' node_modules/@wasichai/*/package.json
```

Expected: every package reports 1 (the overlay of Task 1 is gone: `yarn install` put the published packages back).

- [ ] **Step 2: The whole check, against the published packages**

```bash
yarn format
yarn typecheck && yarn lint && yarn test && yarn build
```

Expected: all green.

- [ ] **Step 3: Look at it**

`yarn dev`, sign in, and with the theme menu: under _Portal tributario_ the tree panel (current leaf, hover, folding)
and an alert (e.g. Infracciones → Nueva acta with no CUIS loaded) look as on `dev`; under light and dark, the classic
sidebar and red/green text alerts.

- [ ] **Step 4: Commit, push and open the PR to `dev`**

```bash
git add package.json yarn.lock
git commit -F - <<'EOF'
chore(deps): @wasichai/* 0.5.0-dev.0

Alert y NavTree, de wasichai/wasichai-ui#14.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
git push -u origin feat/alert-navtree-de-wasichai
gh pr create --base dev --title "refactor(portal): Alert y NavTree de @wasichai/* 0.5.0-dev.0" --body-file - <<'EOF'
`Alerta` y `ArbolNav` subieron a wasichai-ui con caja-ui como segundo usuario (wasichai/wasichai-ui#14): `Alert` en
`@wasichai/ui` y `NavTree` en `@wasichai/core`, con `alerts.css` y `nav.css` en la hoja del tema.

- Las alertas son `Alert` (tonos `success`, `warning`, `danger`, `notice`; ganchos `data-slot="alert"` y `data-tone`).
- El lateral del tema dibuja `NavTree`; `NAV_TREE` y `arbolPara` se quedan, sobre los nodos de core
  (`children`, `alsoAt`, `external`, `icon`), y la hoja activa es `currentNavTreeLeaf`.
- Se borran `Alerta`, `ArbolNav`, `hojaActiva`, `alerts.css` y `nav.css`.
- `@wasichai/*` 0.5.0-dev.0.

Ni light, ni dark, ni portal-tributario cambian.

Refs wasichai/wasichai-ui#14

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```
