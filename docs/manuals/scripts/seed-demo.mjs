#!/usr/bin/env node
// loads the synthetic demo dataset through the REST API, never through SQL: dynamic tables are only
// built by the platform itself (ObjectSchemaManager). idempotent: what exists is skipped, so a rerun creates nothing.
//
// usage: node docs/manuals/scripts/seed-demo.mjs
//   BASE_URL                 app to load, default http://localhost:8090
//   WASICHAI_ADMIN_EMAIL     default admin@wasichai.local (the dev seed's administrator)
//   WASICHAI_ADMIN_PASSWORD  default admin (the dev seed's password, development only)
//   WASICHAI_DEMO_PASSWORD   password given to the demo users, required: nothing is guessed
//   WASICHAI_DEMO_FIXTURE    default docs/manuals/database/fixtures/demo-dataset.json
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const BASE_URL = (process.env.BASE_URL ?? 'http://localhost:8090').replace(/\/$/, '')
const ADMIN_EMAIL = process.env.WASICHAI_ADMIN_EMAIL ?? 'admin@wasichai.local'
const ADMIN_PASSWORD = process.env.WASICHAI_ADMIN_PASSWORD ?? 'admin'
const DEMO_PASSWORD = process.env.WASICHAI_DEMO_PASSWORD
const FIXTURE = process.env.WASICHAI_DEMO_FIXTURE ?? join(here, '..', 'database', 'fixtures', 'demo-dataset.json')
// every write to a requiresReason object says why; it lands in the audit log
const REASON = 'Carga de datos de demostración'

const counts = { created: 0, skipped: 0 }
let token = ''

function fail(message) {
    console.error(`seed-demo: ${message}`)
    process.exit(1)
}

async function call(method, path, body, { allow = [], reason } = {}) {
    const headers = { Accept: 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (reason) headers['X-Change-Reason'] = `UTF-8''${encodeURIComponent(reason)}`
    let res
    try {
        res = await fetch(`${BASE_URL}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
    } catch (e) {
        fail(`${method} ${path}: cannot reach ${BASE_URL} (${e.cause?.code ?? e.message})`)
    }
    const text = await res.text()
    const json = text ? JSON.parse(text) : null
    if (!res.ok && !allow.includes(res.status)) fail(`${method} ${path}: ${res.status} ${text.slice(0, 600)}`)
    return { status: res.status, json }
}

const created = (what) => {
    counts.created++
    console.log(`created  ${what}`)
}
const skipped = (what) => {
    counts.skipped++
    console.log(`exists   ${what}`)
}

// the same set, order aside: a permission or role list read back in another order is no change
const sameSet = (a, b) => JSON.stringify([...a].sort()) === JSON.stringify([...b].sort())

async function main() {
    if (!DEMO_PASSWORD) fail('set WASICHAI_DEMO_PASSWORD, the password the demo users get (local demo only)')
    const data = JSON.parse(readFileSync(FIXTURE, 'utf8'))

    const login = await call('POST', '/api/auth/login', { email: ADMIN_EMAIL, password: ADMIN_PASSWORD })
    token = login.json.token
    // a guard, not a convenience: the dataset only ever goes into its own demo tenant
    const org = (await call('GET', '/api/organizations/current')).json
    if (org.slug !== data.organization) fail(`signed in to tenant '${org.slug}', the dataset is for '${data.organization}': refusing`)
    console.log(`seeding tenant '${org.slug}' at ${BASE_URL}`)

    for (const obj of data.objects) {
        const found = await call('GET', `/api/objects/${obj.name}`, undefined, { allow: [404] })
        if (found.status === 404) {
            await call('POST', '/api/objects', obj)
            created(`object ${obj.name}`)
        } else skipped(`object ${obj.name}`)
    }

    const rels = (await call('GET', '/api/relationships')).json
    for (const rel of data.relationships) {
        if (rels.some((r) => r.name === rel.name)) skipped(`relationship ${rel.name}`)
        else {
            await call('POST', '/api/relationships', rel)
            created(`relationship ${rel.name}`)
        }
    }

    await seedRoles(data)
    await seedOrgUnits(data)
    await seedUsers(data)
    await seedServiceAccounts(data)
    await seedModel(data)
    await seedRecords(data)
    await seedNotifications(data)

    console.log(`done: ${counts.created} created, ${counts.skipped} already there`)
}

async function seedRoles(data) {
    const roles = (await call('GET', '/api/roles')).json
    for (const role of data.roles) {
        let current = roles.find((r) => r.name === role.name)
        if (!current) {
            current = (await call('POST', '/api/roles', { name: role.name, label: role.label, ownRecordsOnly: role.ownRecordsOnly })).json
            created(`role ${role.name}`)
        } else skipped(`role ${role.name}`)
        const wanted = role.permissions.map(([objectName, action]) => `${objectName}.${action}`)
        const held = (current.permissions ?? []).filter((p) => p.allowed).map((p) => `${p.objectName}.${p.action}`)
        if (!sameSet(wanted, held)) {
            const permissions = role.permissions.map(([objectName, action]) => ({ objectName, action }))
            await call('PUT', `/api/roles/${role.name}/permissions`, { permissions })
            created(`permissions of ${role.name}`)
        }
        const wantedFields = role.fieldPermissions.map((f) => `${f.objectName}.${f.fieldName}.${f.read}.${f.write}`)
        const heldFields = (current.fieldPermissions ?? []).map((f) => `${f.objectName}.${f.fieldName}.${f.read}.${f.write}`)
        if (!sameSet(wantedFields, heldFields)) {
            await call('PUT', `/api/roles/${role.name}/field-permissions`, { fields: role.fieldPermissions })
            created(`field permissions of ${role.name}`)
        }
    }
}

async function seedOrgUnits(data) {
    const units = (await call('GET', '/api/org-units')).json
    // parents first: the fixture lists them in that order
    for (const unit of data.orgUnits) {
        if (units.some((u) => u.code === unit.code)) skipped(`org unit ${unit.code}`)
        else {
            await call('POST', '/api/org-units', unit)
            created(`org unit ${unit.code}`)
        }
    }
}

async function seedUsers(data) {
    const users = (await call('GET', '/api/users')).json
    for (const user of data.users) {
        let current = users.find((u) => u.email === user.email)
        if (!current) {
            current = (await call('POST', '/api/users', { email: user.email, displayName: user.displayName, password: DEMO_PASSWORD, roles: user.roles })).json
            created(`user ${user.email}`)
        } else skipped(`user ${user.email}`)
        if (!sameSet(current.orgUnits ?? [], user.orgUnits)) {
            await call('PUT', `/api/users/${current.id}/org-units`, { units: user.orgUnits })
            created(`units of ${user.email}`)
        }
    }
}

async function seedServiceAccounts(data) {
    const accounts = (await call('GET', '/api/service-accounts')).json
    for (const account of data.serviceAccounts) {
        if (accounts.some((a) => a.name === account.name)) skipped(`service account ${account.name}`)
        else {
            // the answer carries the only copy of the secret: it is dropped on purpose, rotate one when needed
            await call('POST', '/api/service-accounts', account)
            created(`service account ${account.name} (secret not kept; rotate it to get one)`)
        }
    }
}

async function seedModel(data) {
    for (const wf of data.workflows) {
        const found = await call('GET', `/api/objects/${wf.objectName}/workflow`, undefined, { allow: [404] })
        if (found.status === 404) {
            await call('PUT', `/api/objects/${wf.objectName}/workflow`, { name: wf.name, label: wf.label, enabled: wf.enabled, definition: wf.definition })
            created(`workflow ${wf.name}`)
        } else skipped(`workflow ${wf.name}`)
    }
    for (const dt of data.documentTypes) {
        const found = await call('GET', `/api/objects/${dt.objectName}/document-types/${dt.name}`, undefined, { allow: [404] })
        if (found.status === 404) {
            await call('POST', `/api/objects/${dt.objectName}/document-types`, { name: dt.name, label: dt.label, prefix: dt.prefix, template: dt.template })
            created(`document type ${dt.name}`)
        } else skipped(`document type ${dt.name}`)
    }
    for (const rule of data.automations) {
        const found = await call('GET', `/api/objects/${rule.objectName}/automations/${rule.name}`, undefined, { allow: [404] })
        if (found.status === 404) {
            await call('POST', `/api/objects/${rule.objectName}/automations`, {
                name: rule.name,
                label: rule.label,
                enabled: rule.enabled,
                definition: rule.definition
            })
            created(`automation ${rule.name}`)
        } else skipped(`automation ${rule.name}`)
    }
    for (const view of data.views) {
        const stored = (await call('GET', `/api/metadata/objects/${view.objectName}/views`)).json
        if (stored.some((v) => v.name === view.name)) skipped(`view ${view.name}`)
        else {
            await call('POST', `/api/objects/${view.objectName}/views`, {
                name: view.name,
                label: view.label,
                isDefault: view.isDefault,
                definition: view.definition
            })
            created(`view ${view.name}`)
        }
    }
    for (const form of data.forms) {
        const stored = (await call('GET', `/api/metadata/objects/${form.objectName}/forms`)).json
        if (stored.some((f) => f.name === form.name)) skipped(`form ${form.name}`)
        else {
            await call('POST', `/api/objects/${form.objectName}/forms`, { name: form.name, label: form.label, definition: form.definition })
            created(`form ${form.name}`)
        }
    }
    for (const page of data.pages) {
        const found = await call('GET', `/api/pages/${page.name}`, undefined, { allow: [404] })
        if (found.status === 404) {
            await call('POST', '/api/pages', page)
            created(`page ${page.name}`)
        } else skipped(`page ${page.name}`)
    }
}

// a record is known by its codigo, unique per object
async function findByCodigo(object, codigo) {
    const page = (await call('GET', `/api/objects/${object}/records?codigo=${encodeURIComponent(codigo)}&size=1`)).json
    return page.content[0] ?? null
}

async function seedRecords(data) {
    const ids = { contribuyente: {}, predio: {}, licencia: {} }
    for (const c of data.records.contribuyentes) {
        const found = await findByCodigo('contribuyente', c.codigo)
        if (found) {
            ids.contribuyente[c.codigo] = found.id
            skipped(`contribuyente ${c.codigo}`)
            continue
        }
        const rec = (await call('POST', '/api/objects/contribuyente/records', { attributes: c })).json
        ids.contribuyente[c.codigo] = rec.id
        created(`contribuyente ${c.codigo}`)
    }
    for (const p of data.records.predios) {
        const found = await findByCodigo('predio', p.codigo)
        if (found) {
            ids.predio[p.codigo] = found.id
            skipped(`predio ${p.codigo}`)
            continue
        }
        const { lote, titular, ...attributes } = p
        const body = { attributes: { ...attributes, titular: ids.contribuyente[titular] }, geometries: { lote } }
        const rec = (await call('POST', '/api/objects/predio/records', body)).json
        ids.predio[p.codigo] = rec.id
        created(`predio ${p.codigo}`)
    }
    for (const l of data.records.licencias) {
        const found = await findByCodigo('licencia', l.codigo)
        if (found) {
            skipped(`licencia ${l.codigo}`)
            continue
        }
        const { estado, solicitante, predio, ...attributes } = l
        const body = { attributes: { ...attributes, solicitante: ids.contribuyente[solicitante], predio: ids.predio[predio] } }
        const rec = (await call('POST', '/api/objects/licencia/records', body, { reason: REASON })).json
        // walk the record to its state through the real transitions, so the history reads as a real case
        for (const transition of data.statePaths[estado]) {
            const why = transition === 'observar' ? 'Falta plano de distribución' : REASON
            await call('POST', `/api/objects/licencia/records/${rec.id}/transitions/${transition}`, undefined, { reason: why })
        }
        created(`licencia ${l.codigo} (${estado})`)
    }
}

async function seedNotifications(data) {
    const page = (await call('GET', '/api/notifications?source=manual&size=200')).json
    for (const n of data.notifications) {
        if (page.content.some((x) => x.title === n.title)) skipped(`notification '${n.title}'`)
        else {
            await call('POST', '/api/notifications', n)
            created(`notification '${n.title}'`)
        }
    }
}

await main()
