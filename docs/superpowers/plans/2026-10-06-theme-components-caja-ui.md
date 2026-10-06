# Theme components, caja-ui: adopt `Alert` and `NavTree` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** caja-ui draws its alerts with `Alert` of `@wasichai/ui` and its tree menu with `NavTree` of `@wasichai/core`,
and deletes its copies of `Alerta`, `ArbolNav` (with `nav.css`) and the unused `BandaTitulo`.

**Architecture:** the same codemod as srtm-ui's renames every `<Alerta>`. `navTree.ts` keeps Tesorería's tree,
`arbolPara`, the permissions and `rastro`, on core's node types; core's `navTreeLeaves`, `isNavTreeGroup` and
`currentNavTreeLeaf` replace `hojasDe`, `esGrupo` and `hojaActiva`; `LateralPortal` draws `NavTree`. Under
`portal-tributario` the alerts gain the prototype's box, which caja-ui never copied (the library's `alerts.css`).

**Tech Stack:** React 19, TypeScript, Vite 8, vitest + testing-library, `@wasichai/*` `0.5.0-dev.0`.

**Spec:** wasichai [`docs/superpowers/specs/2026-10-06-theme-components-design.md`](../specs/2026-10-06-theme-components-design.md).
It depends on the wasichai-ui plan
([`2026-10-06-theme-components-wasichai-ui.md`](2026-10-06-theme-components-wasichai-ui.md)): its Task 6 builds the
packages this plan develops against, and publishes `0.5.0-dev.0`, which Task 4 here needs. The codemod is the srtm-ui
plan's ([`2026-10-06-theme-components-srtm-ui.md`](2026-10-06-theme-components-srtm-ui.md), Task 1, Step 3), repeated
below.

**Repository:** `~/IdeaProjects/caja-ui`, branch `feat/alert-navtree-de-wasichai` off `origin/main` (caja-ui has no
`dev`); PR to `main`.

## Global Constraints

- App identifiers stay Spanish (`HojaNav`, `arbolPara`, `rastro`); comments in English, caveman style. Files copied
  from srtm-ui keep their first-line header (`copiado de srtm-ui…`), updated where what they say changes.
- `.editorconfig`: 2 spaces in TS/TSX, max 160 columns. `yarn format` before each commit (prettier formats `src`,
  including `src/**/*.md`; the root `README.md` is formatted by hand). `tsconfig.json` has `noUnusedLocals`: remove
  what a change leaves unused.
- Light and dark look the same as before. Under `portal-tributario` the tree is the same and the alerts gain their box.
- Tones: `exito` → `success`, `atencion` → `warning`, `error` → `danger`, `aviso` → `notice`; props `tono` → `tone`,
  `titulo` → `title`, `onCerrar` → `onDismiss`. Tree fields: `hijos` → `children`, `externa` → `external`,
  `icono` → `icon` (`tambienEn` → `alsoAt`; no leaf of caja's uses it).
- Conventional Commits (header ≤ 120). Every commit message ends with:

  ```
  Refs wasichai/wasichai-ui#14

  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
  ```

- The overlay of wasichai-ui's local build in `node_modules` (Task 1) is never committed; Task 4 replaces it with the
  published `0.5.0-dev.0`.

## Review Focus

- A leaf with no screen, or one the account may not open, and a non-admin's administration leaf: `arbolPara` still
  drops them, `soloAdmin` now read on the leaf (Task 2, `navTree.test.tsx` as it is).
- The breadcrumb trail on `/duplicado-recibo/001-0000123` (a leaf `conSujeto`): `rastro` still finds the leaf by
  identity through `currentNavTreeLeaf` (Task 2, `navTree.test.tsx`).
- A dialog's own `onCerrar` (`ExplicarElPago`, `PedirDuplicado`, `ActoDeAnulacion`) is not an alert's: it stays
  (Task 1, Step 5).
- `GuardaDeHoja` with permissions that could not be read: its `Alert` keeps saying so, now `warning` (Task 1,
  `guarda.test.tsx` as it is).
- The classic sidebar (light, dark) lists every offered leaf of each group but the external one (Task 2,
  `arbol.test.tsx` as it is).

---

### Task 1: the alerts are `Alert`; the unused `BandaTitulo` goes

**Files:**
- Delete: `src/portal/components/Alerta.tsx`, `src/portal/components/Alerta.test.tsx`,
  `src/portal/components/BandaTitulo.tsx`, `src/portal/components/BandaTitulo.test.tsx`
- Modify (codemod): the 22 files that draw an `Alerta`: `KitDelPortal.tsx`, `auth/LoginPage.tsx`,
  `buzon/ExplicarElPago.tsx`, `buzon/PagosSinEntregar.tsx`, `cobro/ElegirCaja.tsx`, `cobro/Formulario.tsx`,
  `cobro/OrdenesPendientes.tsx`, `cobro/ReciboEmitido.tsx`, `escritura/useEscritura.tsx`,
  `recaudacion/AvanceDeRecaudacionPage.tsx`, `recaudacion/ConciliacionDelDia.tsx`,
  `recaudacion/RecaudacionPorAreaPage.tsx`, `recibo/ListaDeRecibos.tsx`, `recibo/PedirDuplicado.tsx`,
  `recibo/ReciboElegido.tsx`, `shell/GuardaDeHoja.tsx`, `tasas/TasasVigentes.tsx`, `turno/Arqueo.tsx`,
  `turno/CerrarElTurno.tsx`, `turno/CierreCajaPage.tsx`, `turno/ReversarElCierre.tsx`, `turno/TurnoDelDia.tsx`
  (all under `src/portal/`)
- Modify: `src/portal/kit.test.tsx:58-59`, `src/kit/README.md:30`

**Interfaces:**
- Consumes: `Alert` from `@wasichai/ui` (`tone`, `title?`, `children`, `onDismiss?`, `className?`), hooks
  `data-slot="alert"` + `data-tone`.

- [ ] **Step 1: Branch, and put wasichai-ui's local build under `node_modules`**

wasichai-ui's branch `feat/ui-componentes-tema` must be built (`yarn build` there, its plan's Task 6, Step 3).

```bash
cd ~/IdeaProjects/caja-ui
git fetch origin
git switch -c feat/alert-navtree-de-wasichai origin/main
yarn install
for p in core ui; do rsync -a --delete ../wasichai-ui/packages/$p/dist/ node_modules/@wasichai/$p/dist/; done
grep -c 'alert-dismiss' node_modules/@wasichai/ui/dist/index.js
grep -c 'nav-tree-caret' node_modules/@wasichai/core/dist/index.js
```

Expected: both greps print a number above 0. `yarn test` passes as before.

- [ ] **Step 2: Turn the test to the new hooks (it fails)**

`src/portal/kit.test.tsx`, lines 58–59:

```tsx
    expect(alerta).toHaveAttribute('data-slot', 'alert')
    expect(alerta).toHaveAttribute('data-tone', 'danger')
```

Run: `yarn vitest run src/portal/kit.test.tsx`
Expected: FAIL: the alert still carries `data-ui="alerta"`.

- [ ] **Step 3: Delete the copies and run the codemod**

Save this outside the repository, as `$SCRATCH/alerta-a-alert.mjs` (`SCRATCH` is the session's scratchpad, or
`SCRATCH=$(mktemp -d)`; if srtm-ui's plan already saved it there, reuse it):

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
git rm -q src/portal/components/Alerta.tsx src/portal/components/Alerta.test.tsx \
  src/portal/components/BandaTitulo.tsx src/portal/components/BandaTitulo.test.tsx
node "$SCRATCH/alerta-a-alert.mjs" src
```

Expected: it prints the 22 files of the list above.

- [ ] **Step 4: The kit's README**

`src/kit/README.md`, line 30: "su `Alerta` de error" becomes "el `Alert` de `@wasichai/ui` con tono `danger`".

- [ ] **Step 5: Check what is left by hand**

```bash
grep -rnw "Alerta\|BandaTitulo\|CabeceraBanda" src --include='*.ts' --include='*.tsx'
grep -rn "onDismiss" src --include='*.tsx'
```

Expected: the first prints only comments, if any (change them to `Alert`; a comment about `BandaTitulo` goes); the
second prints nothing (no alert of caja used `onCerrar`; the dialogs keep theirs).

- [ ] **Step 6: Run the checks**

```bash
yarn format
yarn typecheck && yarn test && yarn lint
```

Expected: PASS, `kit.test.tsx` included.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -F - <<'EOF'
refactor(portal): las alertas son el Alert de @wasichai/ui

Alerta subió a la librería con caja-ui como segundo usuario (wasichai/wasichai-ui#14). Los tonos pasan a success,
warning, danger y notice, y los ganchos a data-slot="alert" y data-tone. Light y dark no cambian; bajo portal-tributario
las alertas toman la caja del prototipo, que caja-ui no había copiado. Se borra la copia de BandaTitulo, que no se usaba.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 2: the tree menu is `NavTree`

**Files:**
- Delete: `src/portal/shell/ArbolNav.tsx`, `src/themes/portal-tributario/nav.css`
- Modify: `src/portal/shell/navTree.ts`, `src/portal/shell/navTree.test.tsx`, `src/portal/shell/LateralPortal.tsx`,
  `src/portal/shell/AppShell.tsx`, `src/portal/PortalApp.tsx`, `src/portal/pages/InicioPage.tsx`,
  `src/portal/shell/ThemeMenu.test.tsx:64`, `src/themes/portal-tributario/index.css`, `src/themes/parciales.test.tsx`

**Interfaces:**
- Consumes: from `@wasichai/core`, `NavTree` (props `id`, `label`, `title`, `nodes`, `homeTo`, `open`, `groups`,
  `onToggleGroup`, `onNavigate`, `onFold`), `currentNavTreeLeaf`, `isNavTreeGroup`, `navTreeLeaves`, types
  `NavTreeLeaf`, `NavTreeGroup<L>`, `NavTreeNode<L>`.
- Produces: `HojaNav extends NavTreeLeaf { clave?, seOfreceCon?, conSujeto?, soloAdmin? }`,
  `GrupoNav = NavTreeGroup<HojaNav>`, `NodoNav = NavTreeNode<HojaNav>`; `NAV_TREE`, `arbolPara`, `useArbol`,
  `conPantalla`, `loQueFalta` and `rastro` keep their signatures. `esGrupo`, `hojasDe` and `hojaActiva` are gone.

- [ ] **Step 1: Turn the tests to core's tree (they fail)**

`src/portal/shell/navTree.test.tsx`:

1. Imports: `import { arbolPara, hojaActiva, hojasDe, NAV_TREE, rastro } from './navTree'` becomes
   `import { arbolPara, NAV_TREE, rastro } from './navTree'`, plus
   `import { currentNavTreeLeaf, navTreeLeaves } from '@wasichai/core'`.
2. Every `hojasDe(` becomes `navTreeLeaves(`, every `hojaActiva(` becomes `currentNavTreeLeaf(`.
3. The `ver` helper reads `children`:

```tsx
    const ver = (nodos: typeof NAV_TREE): unknown => nodos.map((nodo) => ('children' in nodo ? [nodo.label, ver(nodo.children)] : `${nodo.label} ${nodo.to}`))
```

`src/portal/shell/ThemeMenu.test.tsx`, line 64: `toHaveAttribute('data-ui', 'arbol-nav')` becomes
`toHaveAttribute('data-slot', 'nav-tree')`.

`src/themes/parciales.test.tsx`: `PARCIALES = ['shell.css', 'tabs.css']`; delete the whole `describe('nav.css', …)`;
`contrast` (in the `../test/css` import) and `libraryTokens` are then unused: remove them.

Run: `yarn vitest run src/portal/shell src/themes`
Expected: FAIL: `NAV_TREE` still has `hijos`, and the panel still carries `data-ui="arbol-nav"`.

- [ ] **Step 2: `navTree.ts` on core's nodes**

Rename the fields of `NAV_TREE` in place:

```bash
# macOS sed: no \b. these names only appear as fields of NAV_TREE (the old types are replaced below)
sed -i '' -e 's/hijos: \[/children: [/g' -e 's/externa: /external: /g' -e 's/icono: /icon: /g' src/portal/shell/navTree.ts
```

Then, in `src/portal/shell/navTree.ts`:

1. The header and imports become:

```ts
// copiado de srtm-ui@a1df33a (src/portal/shell/navTree.ts): los nodos y la hoja actual ya son de @wasichai/core (wasichai-ui#14)
// adaptado: diverge de srtm-ui en el árbol (Tesorería con las seis hojas de caja, no los grupos de srtm) y en lo que
// srtm no tiene: la clave de la pantalla de cada hoja (PANTALLAS), su seOfreceCon por permisos (Par, loQueFalta, la
// Oferta de arbolPara, useArbol), conSujeto y el rastro de las migas. la forma de los nodos, su dibujo (NavTree) y la
// hoja actual son de @wasichai/core
import { currentNavTreeLeaf, isNavTreeGroup, useAuth, type NavTreeGroup, type NavTreeLeaf, type NavTreeNode } from '@wasichai/core'
import { Settings } from 'lucide-react'
import { useMemo } from 'react'
import { PANTALLAS } from '../pantallas'
```

2. `interface HojaNav`, `interface GrupoNav`, `type NodoNav` and `esGrupo` become:

```ts
export interface HojaNav extends NavTreeLeaf {
  // a leaf of a module: the key its screen is registered under (PANTALLAS). one with no screen is not drawn
  clave?: ClaveDeHoja
  // when it is offered: any of these alternatives, each a list of pairs the account must all have
  seOfreceCon?: Par[][]
  // its screen takes what is chosen as the last segment of its route (/duplicado-recibo/001-0000123): a reload or a
  // link passed on shows the same. the screen reads it as the route's param `sujeto`
  conSujeto?: boolean
  // for admins only: the administration. no group has it
  soloAdmin?: boolean
}

export type GrupoNav = NavTreeGroup<HojaNav>
export type NodoNav = NavTreeNode<HojaNav>
```

3. `arbolPara` becomes:

```ts
export function arbolPara(nodos: NodoNav[], oferta: Oferta): NodoNav[] {
  return nodos.flatMap((nodo): NodoNav[] => {
    if (!isNavTreeGroup(nodo))
      return (nodo.soloAdmin && !oferta.isAdmin) || (nodo.clave && !oferta.conPantalla(nodo.clave)) || !seOfrece(nodo, oferta.can) ? [] : [nodo]
    const children = arbolPara(nodo.children, oferta)
    return children.length ? [{ ...nodo, children }] : []
  })
}
```

4. Delete `hojasDe` and `hojaActiva` (with their comments). `rastro` becomes:

```ts
// the trail to the leaf current on a path, its groups' labels first; none off the tree
export function rastro(nodos: NodoNav[], pathname: string): string[] {
  const actual = currentNavTreeLeaf(nodos, pathname)
  const camino = (lista: NodoNav[]): string[] | null => {
    for (const nodo of lista) {
      if (nodo === actual) return [nodo.label]
      const resto = isNavTreeGroup(nodo) ? camino(nodo.children) : null
      if (resto) return [nodo.label, ...resto]
    }
    return null
  }
  return actual ? (camino(nodos) ?? []) : []
}
```

- [ ] **Step 3: The other readers of the tree**

`src/portal/shell/AppShell.tsx`: `import { useWasichaiConfig } from '@wasichai/core'` becomes
`import { isNavTreeGroup, navTreeLeaves, useWasichaiConfig } from '@wasichai/core'`;
`import { esGrupo, hojasDe, useArbol } from './navTree'` becomes `import { useArbol } from './navTree'`. In
`LateralClasico`:

```tsx
  const grupos = useArbol().filter(isNavTreeGroup)
```

```tsx
              {navTreeLeaves(grupo.children)
                .filter((hoja) => !hoja.external)
```

`src/portal/PortalApp.tsx`: add `navTreeLeaves` to its `@wasichai/core` import; `import { hojasDe, NAV_TREE } from './shell/navTree'`
becomes `import { NAV_TREE } from './shell/navTree'`; `hojasDe(NAV_TREE)` becomes `navTreeLeaves(NAV_TREE)`.

`src/portal/pages/InicioPage.tsx`: `import { useAuth } from '@wasichai/core'` becomes
`import { navTreeLeaves, useAuth } from '@wasichai/core'`; `hojasDe` leaves the `../shell/navTree` import; both
`hojasDe(` become `navTreeLeaves(`.

- [ ] **Step 4: `LateralPortal` draws `NavTree`; delete `ArbolNav` and `nav.css`**

`src/portal/shell/LateralPortal.tsx` (its header line stays):

```tsx
import { NavTree } from '@wasichai/core'
import { useState } from 'react'
import type { LateralProps } from './comun'
import { useArbol } from './navTree'
import { guardarNav, leerNav } from './panelLateral'

// the portal's lateral: the screens of Tesorería the account is offered (useArbol) in core's NavTree, its groups
// remembered for the browser tab like the panel (usePanelLateral)
export function LateralPortal({ abierto, onNavegar, onPlegar }: LateralProps) {
  const nodos = useArbol()
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
      title="Ventanilla"
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

- [ ] **Step 5: Run the checks**

```bash
yarn format
yarn typecheck && yarn test && yarn lint
grep -rn "ArbolNav\|hojaActiva\|hojasDe\|esGrupo\|\.hijos\|\.externa\b\|arbol-\(nav\|grupo\|hoja\|caret\)" src --include='*.ts' --include='*.tsx'
```

Expected: PASS (`navTree.test.tsx`, `arbol.test.tsx`, `ThemeMenu.test.tsx`, `guarda.test.tsx` included). The grep
prints nothing. If `useArbol().filter(isNavTreeGroup)` does not narrow to `GrupoNav[]`, write
`.filter((nodo) => isNavTreeGroup(nodo))`.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -F - <<'EOF'
refactor(portal): el menú en árbol es el NavTree de @wasichai/core

ArbolNav, esGrupo, hojasDe, hojaActiva y nav.css subieron a la librería (NavTree, isNavTreeGroup, navTreeLeaves,
currentNavTreeLeaf y su parcial en la hoja del tema), con caja-ui como segundo usuario. El árbol de Tesorería, sus
permisos y el rastro de las migas se quedan, sobre los nodos de core. El árbol se ve igual en todos los temas.

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 3: the README says where the pieces are

**Files:**
- Modify: `README.md` (hand-formatted: 160 columns)

- [ ] **Step 1: The portal's bullets**

1. The bullet "Las piezas copiadas de `srtm-ui@a1df33a` (shell, login, temas, `Alerta`, `BandaTitulo`, `KitDelPortal`)
   llevan arriba…" lists "(shell, login, temas, `KitDelPortal`)"; in its list of adapted files nothing changes.
2. After it, a new bullet:

```markdown
- **`Alerta` y `ArbolNav` ya subieron** (wasichai-ui#14, `@wasichai/*` 0.5.0-dev.0): las alertas son `Alert` de
  `@wasichai/ui` y el árbol del tema es `NavTree` de `@wasichai/core`, con sus parciales en la hoja de la librería
  (`alerts.css`, `nav.css`). `navTree.ts` se queda con el árbol de Tesorería, los permisos y el rastro, sobre los nodos
  de core. Bajo _Portal tributario_ las alertas tienen ahora la caja del prototipo (fondo suave, borde y relleno por
  tono), que caja-ui no había copiado. La copia de `BandaTitulo`, que nada usaba, se borró.
```

- [ ] **Step 2: Check and commit**

```bash
awk 'length > 160 {print FILENAME": "FNR}' README.md
git add README.md
git commit -F - <<'EOF'
docs: Alert y NavTree vienen de @wasichai/*

Refs wasichai/wasichai-ui#14

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

Expected: `awk` prints nothing.

---

### Task 4: `@wasichai/*` 0.5.0-dev.0 and the PR

Starts once wasichai-ui's `v0.5.0-dev.0` is published (its plan's Task 6, Step 6).

**Files:**
- Modify: `package.json`, `yarn.lock`

- [ ] **Step 1: Move every `@wasichai/*` to the pre-release**

In `package.json`, every `"@wasichai/…": "0.4.0"` (`core`, `forms`, `pages`, `ui`, `views` and the dev dependency
`testing`) becomes `"0.5.0-dev.0"`.

```bash
yarn install
grep -c '"version": "0.5.0-dev.0"' node_modules/@wasichai/*/package.json
```

Expected: every package reports 1 (the overlay of Task 1 is gone).

- [ ] **Step 2: The whole check, against the published packages**

```bash
yarn format
yarn typecheck && yarn lint && yarn test && yarn build
```

Expected: all green.

- [ ] **Step 3: Look at it**

`yarn dev` (port 5181, caja-backend on 8091), sign in as `admin@wasichai.local` / `admin`: under _Portal tributario_ the
tree (Ventanilla, Tesorería and its six leaves, the current one marked) looks as before, and an alert (e.g. Caja
tributaria with no caja chosen) now has its box; under light and dark, the classic sidebar and text alerts.

- [ ] **Step 4: Commit, push and open the PR to `main`**

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
gh pr create --base main --title "refactor(portal): Alert y NavTree de @wasichai/* 0.5.0-dev.0" --body-file - <<'EOF'
`Alerta` y `ArbolNav` subieron a wasichai-ui con caja-ui como segundo usuario (wasichai/wasichai-ui#14): `Alert` en
`@wasichai/ui` y `NavTree` en `@wasichai/core`, con `alerts.css` y `nav.css` en la hoja del tema.

- Las alertas son `Alert` (tonos `success`, `warning`, `danger`, `notice`; ganchos `data-slot="alert"` y `data-tone`).
  Bajo portal-tributario ganan la caja del prototipo, que caja-ui no había copiado.
- El lateral del tema dibuja `NavTree`; el árbol de Tesorería, sus permisos y el rastro se quedan, sobre los nodos de
  core, y `navTreeLeaves`, `isNavTreeGroup` y `currentNavTreeLeaf` sustituyen a `hojasDe`, `esGrupo` y `hojaActiva`.
- Se borran `Alerta`, `ArbolNav`, `nav.css` y la copia sin uso de `BandaTitulo`.
- `@wasichai/*` 0.5.0-dev.0.

Refs wasichai/wasichai-ui#14

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_011QDrQbX7Zm9cMZZyHF1C4R
EOF
```

---

### Task 5: tell #14 what went up (after both apps' PRs merge)

- [ ] **Step 1: Ask the user, then comment**

It posts on GitHub: ask first. Then:

```bash
gh issue comment 14 --repo wasichai/wasichai-ui --body-file - <<'EOF'
Subieron `Alert` (`@wasichai/ui`) y `NavTree` (`@wasichai/core`), con `alerts.css` y `nav.css` en la hoja
`portal-tributario`, en `0.5.0-dev.0`: caja-ui fue el segundo usuario. srtm-ui y caja-ui ya borraron sus copias.

Siguen pendientes, sin segundo usuario: `BandaTitulo`/`CabeceraBanda`, `PasosGalon`, `BarraInstruccion` y la idea de
`useVarianteTema` (los shells por tema, que ADR-035 deja en la app).

Spec y planes: wasichai `docs/superpowers/specs/2026-10-06-theme-components-design.md` y
`docs/superpowers/plans/2026-10-06-theme-components-*.md`.
EOF
```
