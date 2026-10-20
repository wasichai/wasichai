#!/usr/bin/env node
// takes the manuals' screenshots from the real app with playwright, in both ui languages.
// it only reads and opens screens: nothing is saved, except each demo user's ui preferences (language, theme),
// which it sets before and puts back after.
//
// usage: node docs/manuals/scripts/screenshots.mjs [es|en ...]
//   WEB_URL                the web, default http://localhost:4173 (full-sample web, `yarn preview`)
//   API_URL                the server, default http://localhost:8090
//   WASICHAI_DEMO_PASSWORD the demo users' password, as given to seed-demo.mjs
//   CHROMIUM_PATH          a chromium to use instead of playwright's own
// output: docs/manuals/assets/screenshots/<lang>/{user,admin}/<nn>-<name>.png
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const WEB = (process.env.WEB_URL ?? 'http://localhost:4173').replace(/\/$/, '')
const API = (process.env.API_URL ?? 'http://localhost:8090').replace(/\/$/, '') + '/api'
const DEMO_PASSWORD = process.env.WASICHAI_DEMO_PASSWORD
const LANGS = process.argv.slice(2).length ? process.argv.slice(2) : ['es', 'en']
const ADMIN = { email: 'admin@wasichai.local', password: process.env.WASICHAI_ADMIN_PASSWORD ?? 'admin' }
const users = {
    admin: ADMIN,
    registrador: { email: 'registrador@demo.example', password: DEMO_PASSWORD },
    revisor: { email: 'revisor@demo.example', password: DEMO_PASSWORD },
    consulta: { email: 'consulta@demo.example', password: DEMO_PASSWORD }
}
const VIEWPORT = { width: 1280, height: 860 }
// 1.5x: crisp when the pdf is zoomed or printed, without the weight of 2x
const SCALE = 1.5
// the shell's sidebar (wasichai-ui AppShell): a 'content' crop leaves it out
const SIDEBAR = 240

if (!DEMO_PASSWORD) {
    console.error('screenshots: set WASICHAI_DEMO_PASSWORD (the demo users\' password)')
    process.exit(1)
}

async function api(token, method, path, body) {
    const res = await fetch(API + path, {
        method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'application/json' },
        body: body ? JSON.stringify(body) : undefined
    })
    if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${await res.text()}`)
    return res.status === 204 ? null : res.json()
}

async function tokenOf(user) {
    const res = await fetch(API + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(user) })
    if (!res.ok) throw new Error(`login ${user.email}: ${res.status}`)
    return (await res.json()).token
}

// the records each screen shows, found by their business key so a rebuilt database gives the same pictures
async function lookups(token) {
    const one = async (object, query) => (await api(token, 'GET', `/objects/${object}/records?${query}&size=1`)).content[0]
    const inState = async (state) => {
        const all = (await api(token, 'GET', '/objects/licencia/records?size=200&sort=codigo')).content
        return all.find((r) => r.state === state)
    }
    const approved = await inState('aprobada')
    const documents = await api(token, 'GET', `/objects/licencia/records/${approved.id}/documents`)
    return {
        review: await inState('en_revision'),
        draft: await inState('borrador'),
        approved,
        document: documents[0],
        predio: await one('predio', 'codigo=P-0007'),
        contribuyente: await one('contribuyente', 'codigo=C-0023')
    }
}

let browser
const launch = (lang) =>
    chromium.launch({
        ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}),
        // chromium takes the language of its own controls (date pickers) from --lang, not from the page
        args: [`--lang=${lang === 'es' ? 'es-PE' : 'en-US'}`],
        env: { ...process.env, LANG: lang === 'es' ? 'es_PE.UTF-8' : 'en_US.UTF-8' }
    })

async function settle(page) {
    await page.waitForLoadState('networkidle')
    // a query still loading draws a skeleton; never photograph one
    await page.waitForFunction(() => !document.querySelector('[data-slot="query-state"][data-state="loading"], [aria-busy="true"]'), null, { timeout: 15000 }).catch(() => {})
    await page.evaluate(() => document.fonts.ready)
    await page.waitForTimeout(500)
}

async function session(lang, who) {
    const user = users[who]
    const token = await tokenOf(user)
    // what the app restores at sign-in wins over localStorage, so the language goes to the server first
    await api(token, 'PUT', '/auth/me/preferences', { locale: lang, theme: 'light' })
    const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: SCALE, locale: lang === 'es' ? 'es-PE' : 'en-US', timezoneId: 'America/Lima', colorScheme: 'light' })
    await context.addInitScript((l) => {
        localStorage.setItem('wasichai.lang', l)
        localStorage.setItem('wasichai.theme', 'light')
    }, lang)
    const page = await context.newPage()
    await page.goto(WEB + '/login')
    await page.fill('#email', user.email)
    await page.fill('#password', user.password)
    await page.locator('form button[type="submit"]').click()
    await page.waitForURL(WEB + '/')
    await settle(page)
    return { page, context, token }
}

async function contentHeight(page) {
    const tallest = await page.evaluate(() =>
        Math.max(
            document.documentElement.scrollHeight,
            ...[...document.querySelectorAll('*')]
                .filter((e) => /(auto|scroll)/.test(getComputedStyle(e).overflowY) && e.scrollHeight > e.clientHeight + 4)
                .map((e) => e.scrollHeight + e.getBoundingClientRect().top)
        )
    )
    return Math.min(Math.max(VIEWPORT.height, Math.ceil(tallest)), 3200)
}

function shooter(lang) {
    // crop: 'content' leaves the sidebar out; a locator crops to that element, with `pad` pixels around it
    return async (page, file, { fullPage = false, locator, crop, pad = 0 } = {}) => {
        const path = join(root, 'assets', 'screenshots', lang, file + '.png')
        mkdirSync(dirname(path), { recursive: true })
        await settle(page)
        if (locator && pad) {
            const box = await locator.boundingBox()
            const clip = { x: Math.max(0, box.x - pad), y: Math.max(0, box.y - pad), width: box.width + 2 * pad, height: box.height + 2 * pad }
            await page.screenshot({ path, clip })
        } else if (locator) await locator.screenshot({ path })
        else {
            // the shell scrolls an inner container, so a full page means a viewport as tall as that content
            const height = fullPage ? await contentHeight(page) : VIEWPORT.height
            if (height !== VIEWPORT.height) {
                await page.setViewportSize({ width: VIEWPORT.width, height })
                await settle(page)
            }
            const clip = crop === 'content' ? { x: SIDEBAR, y: 0, width: VIEWPORT.width - SIDEBAR, height } : undefined
            await page.screenshot({ path, clip })
            if (height !== VIEWPORT.height) await page.setViewportSize(VIEWPORT)
        }
        console.log(`shot  ${lang}/${file}.png`)
    }
}

// the sign-in form is the card itself
const loginCard = (page) => page.locator('form').first()

async function go(page, path) {
    await page.goto(WEB + path)
    await settle(page)
}

// a radix select: open the trigger, pick the option by its visible text
async function choose(page, trigger, option) {
    await trigger.click()
    await page.getByRole('option', { name: option }).first().click()
    await settle(page)
}

async function run(lang) {
    const shot = shooter(lang)
    const t = (es, en) => (lang === 'es' ? es : en)

    // the login screen, before signing in: the password field shows dots only
    {
        const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: SCALE, colorScheme: 'light' })
        await context.addInitScript((l) => {
            localStorage.setItem('wasichai.lang', l)
            localStorage.setItem('wasichai.theme', 'light')
        }, lang)
        const page = await context.newPage()
        await go(page, '/login')
        await page.fill('#email', users.registrador.email)
        await page.fill('#password', 'x'.repeat(10))
        await shot(page, 'user/01-login', { locator: loginCard(page), pad: 32 })
        // a wrong password: the answer the person sees, nothing else happens (login limits are off by default)
        await page.locator('form button[type="submit"]').click()
        await page.waitForTimeout(800)
        await shot(page, 'user/20-login-error', { locator: loginCard(page), pad: 32 })
        await context.close()
    }

    const { page, context, token } = await session(lang, 'admin')
    const data = await lookups(token)

    // user manual, as the administrator sees the shared screens
    await shot(page, 'user/02-home')
    await go(page, '/data/objects/licencia/records')
    await shot(page, 'user/03-record-list')
    await page.getByPlaceholder(t('Buscar', 'Search')).fill('Restaurante')
    await page.waitForTimeout(700)
    await shot(page, 'user/04-record-search', { crop: 'content' })
    await go(page, '/data/objects/licencia/records')
    await page.getByRole('button', { name: new RegExp(t('Fecha de solicitud', 'Fecha de solicitud')) }).click()
    await shot(page, 'user/05-record-sort', { crop: 'content' })
    await go(page, '/data/objects/licencia/records')
    await choose(page, page.getByRole('combobox', { name: t('Vista', 'View') }), 'Restaurantes')
    await shot(page, 'user/06-record-view', { crop: 'content' })
    await go(page, '/data/objects/licencia/records')
    await page.getByRole('button', { name: t('Página siguiente', 'Next page') }).scrollIntoViewIfNeeded()
    await shot(page, 'user/07-record-pager', { crop: 'content' })
    await go(page, '/data/objects/licencia/records/new')
    await shot(page, 'user/08-record-new', { fullPage: true, crop: 'content' })
    // saving an empty form: the client stops it before any request, so nothing is written
    await page.getByRole('button', { name: t('Guardar', 'Save') }).last().click()
    await page.waitForTimeout(500)
    await shot(page, 'user/21-validation-errors', { fullPage: true, crop: 'content' })
    await go(page, `/data/objects/licencia/records/${data.review.id}`)
    await shot(page, 'user/09-record-detail', { fullPage: true })
    await page.getByRole('tab', { name: 'Predio y solicitante' }).click()
    await shot(page, 'user/10-record-related', { crop: 'content' })
    await page.getByRole('tab', { name: 'Historial' }).click()
    await shot(page, 'user/11-record-history', { fullPage: true, crop: 'content' })
    await go(page, `/data/objects/licencia/records/${data.approved.id}`)
    // the issued documents panel closes the page; the shell scrolls an inner container, not the window
    await page.getByText(t('Documentos emitidos', 'Issued documents')).first().evaluate((e) => e.scrollIntoView({ block: 'start' }))
    await shot(page, 'user/12-record-documents', { crop: 'content' })
    await go(page, `/documents/${data.document.id}/print`)
    await shot(page, 'user/13-document-print')
    await go(page, `/data/objects/predio/records/${data.predio.id}`)
    await page.getByRole('tab', { name: t('Mapa', 'Map') }).click()
    await page.waitForTimeout(1500)
    await shot(page, 'user/14-predio-map-tab', { crop: 'content' })
    await go(page, '/gis/map?object=predio&geometry=lote')
    await page.waitForTimeout(1500)
    await shot(page, 'user/15-map')

    // admin manual
    await go(page, '/data/objects')
    await shot(page, 'admin/01-objects', { crop: 'content' })
    await go(page, '/data/objects/licencia/edit')
    await shot(page, 'admin/02-object-editor', { crop: 'content' })
    await page.getByRole('heading', { name: t('Campos', 'Fields') }).scrollIntoViewIfNeeded()
    await page.mouse.wheel(0, 300)
    await shot(page, 'admin/03-object-fields', { crop: 'content' })
    await go(page, '/data/objects/new')
    await shot(page, 'admin/04-object-new', { crop: 'content' })
    await go(page, '/data/relationships')
    await shot(page, 'admin/05-relationships', { crop: 'content' })
    await go(page, '/admin/users')
    await shot(page, 'admin/06-users', { crop: 'content' })
    await go(page, '/admin/roles')
    await shot(page, 'admin/07-roles', { crop: 'content' })
    await go(page, '/admin/permissions')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Consulta/)
    await choose(page, page.locator('[data-slot="select-trigger"]').last(), /Contribuyente/)
    await shot(page, 'admin/08-permissions', { fullPage: true, crop: 'content' })
    await go(page, '/admin/service-accounts')
    await shot(page, 'admin/09-service-accounts', { crop: 'content' })
    await go(page, '/admin/audit')
    await page.locator('tbody tr button').first().click()
    await shot(page, 'admin/10-audit', { crop: 'content' })
    await go(page, '/builder/views')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await shot(page, 'admin/11-views-builder', { fullPage: true, crop: 'content' })
    await go(page, '/builder/forms')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await shot(page, 'admin/12-forms-builder', { fullPage: true, crop: 'content' })
    await go(page, '/builder/pages')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await shot(page, 'admin/13-pages-builder', { crop: 'content' })
    await go(page, '/builder/documents')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await page.getByText('Certificado de licencia').first().click()
    await shot(page, 'admin/14-document-types', { fullPage: true, crop: 'content' })
    await go(page, '/automation/workflows')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await page.waitForTimeout(1000)
    await shot(page, 'admin/15-workflow-canvas', { crop: 'content' })
    await go(page, '/automation/rules')
    await choose(page, page.locator('[data-slot="select-trigger"]').first(), /Licencia/)
    await page.getByText('Emitir certificado al aprobar').first().click()
    await shot(page, 'admin/16-automation-rules', { fullPage: true, crop: 'content' })
    await go(page, '/automation/runs')
    await shot(page, 'admin/17-automation-runs', { crop: 'content' })
    await go(page, '/gis/layers')
    await shot(page, 'admin/18-gis-layers', { crop: 'content' })
    await context.close()

    // what each role sees: the same screens, other answers
    {
        const s = await session(lang, 'revisor')
        await go(s.page, `/data/objects/licencia/records/${data.review.id}`)
        await shot(s.page, 'user/16-transitions-revisor', { locator: s.page.locator('[data-slot="card"]').filter({ hasText: 'Trámite de licencia' }).first() })
        await s.context.close()
    }
    {
        const s = await session(lang, 'registrador')
        await go(s.page, `/data/objects/licencia/records/${data.review.id}`)
        await shot(s.page, 'user/17-transitions-registrador', { locator: s.page.locator('[data-slot="card"]').filter({ hasText: 'Trámite de licencia' }).first() })
        await s.context.close()
    }
    {
        const s = await session(lang, 'consulta')
        await shot(s.page, 'user/18-home-consulta')
        await go(s.page, `/data/objects/contribuyente/records/${data.contribuyente.id}`)
        await shot(s.page, 'user/19-field-permissions-consulta', { crop: 'content' })
        // the screen offers save, the server refuses: a read-only role gets 403 and nothing changes
        await s.page.getByRole('button', { name: t('Guardar', 'Save') }).first().click()
        await s.page.waitForTimeout(800)
        await shot(s.page, 'user/22-forbidden-consulta', { crop: 'content' })
        await s.context.close()
    }
}

try {
    for (const lang of LANGS) {
        browser = await launch(lang)
        await run(lang)
        await browser.close()
    }
} finally {
    // hand the demo users back their defaults: the browser's language and the system theme
    for (const user of Object.values(users)) {
        const token = await tokenOf(user).catch(() => null)
        if (token) await api(token, 'PUT', '/auth/me/preferences', { locale: null, theme: 'system' }).catch(() => {})
    }
    await browser?.close().catch(() => {})
}
