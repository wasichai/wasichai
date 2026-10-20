#!/usr/bin/env node
// checks the manuals' sources before a build is trusted. exits 1 on any problem, listing each.
// usage: node docs/manuals/scripts/validate.mjs   (needs bundler + the gems of docs/manuals/Gemfile)
//  - every book converts with no warning, unresolved reference or missing include/image
//  - es and en have the same files and the same anchors, file by file
//  - every image a source names exists; every screenshot and diagram is used
//  - line length (160), final newline, no trailing spaces, no TODO, no absolute local paths, no secret-looking strings
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const LANGS = ['es', 'en']
const BOOKS = ['index', 'user-manual', 'admin-manual', 'system-manual']
const problems = []
const problem = (message) => problems.push(message)

const walk = (dir) =>
    readdirSync(dir).flatMap((name) => {
        const path = join(dir, name)
        return statSync(path).isDirectory() ? walk(path) : [path]
    })
const sources = (lang) => walk(join(root, lang)).filter((f) => f.endsWith('.adoc'))
const rel = (path) => relative(root, path)

// 1. conversion: asciidoctor reports unresolved xrefs only in verbose mode
for (const lang of LANGS) {
    for (const book of BOOKS) {
        const file = join(root, lang, `${book}.adoc`)
        if (!existsSync(file)) {
            problem(`${rel(file)}: missing`)
            continue
        }
        let log = ''
        try {
            log = execFileSync('bundle', ['exec', 'asciidoctor', '--verbose', '-a', 'linkcss', '-o', '/dev/null', file], {
                cwd: root,
                encoding: 'utf8',
                stdio: ['ignore', 'pipe', 'pipe']
            })
        } catch (e) {
            log = `${e.stdout ?? ''}${e.stderr ?? ''}`
            if (!log) problem(`${rel(file)}: asciidoctor failed: ${e.message}`)
        }
        for (const line of log.split('\n').filter((l) => /(WARNING|ERROR|possible invalid reference)/.test(l))) {
            problem(`${rel(file)}: ${line.replace(/^asciidoctor: /, '')}`)
        }
    }
}

// 2. language parity: same chapters, same anchors in each
const anchors = (file) => [...readFileSync(file, 'utf8').matchAll(/^\[\[([a-z0-9-]+)\]\]/gm)].map((m) => m[1]).sort()
const files = Object.fromEntries(LANGS.map((lang) => [lang, sources(lang).map((f) => relative(join(root, lang), f)).sort()]))
for (const f of new Set([...files.es, ...files.en])) {
    const [es, en] = LANGS.map((lang) => join(root, lang, f))
    if (!existsSync(es) || !existsSync(en)) {
        problem(`${f}: present in ${existsSync(es) ? 'es' : 'en'} only`)
        continue
    }
    const [a, b] = [anchors(es), anchors(en)]
    const missing = a.filter((x) => !b.includes(x)).concat(b.filter((x) => !a.includes(x)))
    if (missing.length) problem(`${f}: anchors differ between es and en: ${missing.join(', ')}`)
}

// 3. images: what is named exists, what exists is named
const used = new Set()
for (const lang of LANGS) {
    for (const file of sources(lang)) {
        for (const m of readFileSync(file, 'utf8').matchAll(/image::?([^\[\s]+)\[/g)) {
            const target = m[1].replace('{lang}', lang)
            used.add(target)
            if (!existsSync(join(root, 'assets', target))) problem(`${rel(file)}: image not found: ${target}`)
        }
    }
}
const generated = [...walk(join(root, 'assets', 'screenshots')), ...readdirSync(join(root, 'assets', 'diagrams')).filter((f) => f.endsWith('.png')).map((f) => join(root, 'assets', 'diagrams', f))]
for (const png of generated) {
    const target = relative(join(root, 'assets'), png)
    if (png.endsWith('.png') && !used.has(target)) problem(`${rel(png)}: not used by any manual`)
}

// 4. text hygiene over every hand-written file of the manuals
const text = walk(root).filter(
    (f) =>
        !/(node_modules|\.bundle|output|backups|assets\/screenshots)/.test(f) &&
        /\.(adoc|mjs|sh|sql|yml|css|mmd|json)$/.test(f) &&
        !f.endsWith('demo-dataset.json') &&
        !f.endsWith('demo-manifest.json')
)
const secret = /(eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY|sk-ant-[A-Za-z0-9]|AKIA[0-9A-Z]{16})/
for (const file of text) {
    const content = readFileSync(file, 'utf8')
    if (!content.endsWith('\n')) problem(`${rel(file)}: no final newline`)
    content.split('\n').forEach((line, i) => {
        const at = `${rel(file)}:${i + 1}`
        if ([...line].length > 160) problem(`${at}: longer than 160 columns`)
        if (/[ \t]+$/.test(line)) problem(`${at}: trailing whitespace`)
        if (/\b(TODO|FIXME|TBD)\b/.test(line)) problem(`${at}: unfinished marker`)
        if (/(\/home\/|\/tmp\/claude|[A-Z]:\\\\Users)/.test(line)) problem(`${at}: absolute local path`)
        if (secret.test(line)) problem(`${at}: looks like a secret`)
    })
}
// the dump holds demo password hashes on purpose, never a token or a key
const dump = join(root, 'database', 'backups', 'wasichai-demo.sql')
if (existsSync(dump) && secret.test(readFileSync(dump, 'utf8'))) problem(`${rel(dump)}: looks like it holds a secret`)

if (problems.length) {
    console.error(`validate: ${problems.length} problem(s)\n  ${problems.join('\n  ')}`)
    process.exit(1)
}
console.log(`validate: OK (${LANGS.length * BOOKS.length} books, ${files.es.length} sources per language, ${used.size} images)`)
