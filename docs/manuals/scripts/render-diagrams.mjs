#!/usr/bin/env node
// renders every assets/diagrams/src/<lang>/*.mmd to assets/diagrams/<lang>-<name>.png with mermaid inside chromium (playwright).
// png, not svg: mermaid's svg puts labels in foreignObject, which the pdf converter cannot draw.
// usage: node docs/manuals/scripts/render-diagrams.mjs   (CHROMIUM_PATH overrides the browser)
import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const src = join(root, 'assets', 'diagrams', 'src')
const out = join(root, 'assets', 'diagrams')
const mermaidJs = join(root, 'node_modules', 'mermaid', 'dist', 'mermaid.min.js')

// the manuals' palette (theme/pdf/wasichai-theme.yml), so a diagram reads as part of the page
const config = {
    startOnLoad: false,
    theme: 'base',
    fontFamily: 'IBM Plex Sans, Arial, sans-serif',
    themeVariables: {
        fontSize: '15px',
        primaryColor: '#ebf2ff',
        primaryBorderColor: '#3263c3',
        primaryTextColor: '#1b1f29',
        secondaryColor: '#f3f5f8',
        tertiaryColor: '#ffffff',
        lineColor: '#656972',
        clusterBkg: '#f3f5f8',
        clusterBorder: '#9ea5b2',
        edgeLabelBackground: '#ffffff'
    },
    flowchart: { htmlLabels: true, curve: 'basis' },
    er: { useMaxWidth: false },
    sequence: { useMaxWidth: false, mirrorActors: false }
}

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {})
const page = await browser.newPage({ deviceScaleFactor: 2 })
const fonts = join(root, 'theme', 'fonts', 'web')
await page.setContent(`<!doctype html><html><head><style>
  @font-face { font-family: 'IBM Plex Sans'; src: url('file://${fonts}/IBMPlexSans-Regular.woff2'); }
  @font-face { font-family: 'IBM Plex Sans'; font-weight: 600; src: url('file://${fonts}/IBMPlexSans-SemiBold.woff2'); }
  body { margin: 0; background: #fff; } #d { display: inline-block; padding: 16px; }
</style></head><body><div id="d"></div></body></html>`)
await page.addScriptTag({ path: mermaidJs })
await page.evaluate((c) => window.mermaid.initialize(c), config)

const files = readdirSync(src, { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .flatMap((d) => readdirSync(join(src, d.name)).filter((f) => f.endsWith('.mmd')).map((f) => join(d.name, f)))
    .sort()
for (const file of files) {
    const name = file.replace(/\.mmd$/, '').replace('/', '-')
    const code = readFileSync(join(src, file), 'utf8')
    await page.evaluate(async ({ id, code }) => {
        await document.fonts.ready
        const { svg } = await window.mermaid.render(id, code)
        document.getElementById('d').innerHTML = svg
    }, { id: `m-${name}`, code })
    await page.locator('#d').screenshot({ path: join(out, `${name}.png`), omitBackground: false })
    console.log(`diagram  ${name}.png`)
}
await browser.close()
