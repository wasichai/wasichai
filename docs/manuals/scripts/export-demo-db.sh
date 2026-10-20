#!/usr/bin/env bash
# exports the demo database to plain, reviewable sql (schema + data of both wasichai schemas) and writes its manifest.
# read-only on the database. refuses any database whose name does not end in _demo.
# usage: docs/manuals/scripts/export-demo-db.sh
#   WASICHAI_DB_HOST/PORT/NAME/USERNAME/PASSWORD   as the app reads them (defaults: localhost, 5432, -, wasichai, wasichai)
# needs pg_dump and psql of PostgreSQL 18 (a client older than the server cannot dump it)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${WASICHAI_DB_NAME:-}"
[[ "$DB" == *_demo ]] || { echo "export: WASICHAI_DB_NAME must name a demo database (*_demo), got '${DB}'" >&2; exit 1; }
export PGHOST="${WASICHAI_DB_HOST:-localhost}" PGPORT="${WASICHAI_DB_PORT:-5432}" PGUSER="${WASICHAI_DB_USERNAME:-wasichai}"
export PGPASSWORD="${WASICHAI_DB_PASSWORD:-wasichai}" PGDATABASE="$DB"
OUT="$ROOT/database/backups/wasichai-demo.sql"
MANIFEST="$ROOT/database/manifests/demo-manifest.json"
mkdir -p "$(dirname "$OUT")" "$(dirname "$MANIFEST")"

psql -XtAc 'select 1' >/dev/null || { echo "export: cannot reach $PGUSER@$PGHOST:$PGPORT/$DB" >&2; exit 1; }

# the two extensions the platform needs go in too, so an empty database takes the dump. vector stays out on purpose:
# core only uses it when the server has it, and naming it would make the restore require pgvector.
# --column-inserts: one readable, diffable INSERT per row; --no-owner/--no-privileges: restorable under another role.
# --restrict-key: a fixed key, so two exports of the same data give the same file
pg_dump --format=plain --encoding=UTF8 --no-owner --no-privileges --column-inserts --quote-all-identifiers \
    --restrict-key=wasichaidemo --schema=wasichai --schema=app_data --extension=pgcrypto --extension=postgis --file="$OUT.tmp"
# the dump's own header names the time it ran; drop it so the file only changes when the data does
grep -v '^-- Dumped \(from\|by\) ' "$OUT.tmp" > "$OUT" && rm "$OUT.tmp"

counts=$(psql -XtA -F $'\t' <<'SQL'
select table_schema || '.' || table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
from information_schema.tables
where table_schema in ('wasichai', 'app_data') and table_type = 'BASE TABLE'
order by 1
SQL
)
flyway=$(psql -XtA -F $'\t' -c "select tablename from pg_tables where schemaname = 'wasichai' and tablename like 'flyway_history_%' order by 1" |
    while read -r t; do printf '%s\t%s\n' "$t" "$(psql -XtAc "select coalesce((select version from \"wasichai\".\"$t\" where success and version is not null order by installed_rank desc limit 1), '')")"; done)

python3 - "$MANIFEST" "$OUT" <<PY
import hashlib, json, sys, datetime
manifest, dump = sys.argv[1], sys.argv[2]
rows = {}
for line in """$counts""".strip().splitlines():
    t, c = line.split('\t'); rows[t] = int(c)
flyway = {}
for line in """$flyway""".strip().splitlines():
    t, v = line.split('\t'); flyway[t.removeprefix('flyway_history_')] = v
data = {
    "artifact": "database/backups/wasichai-demo.sql",
    "sha256": hashlib.sha256(open(dump, 'rb').read()).hexdigest(),
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat(),
    "source": {"database": "$DB", "server": """$(psql -XtAc 'show server_version')""".strip(),
               "postgis": """$(psql -XtAc "select coalesce((select extversion from pg_extension where extname = 'postgis'), '')")""".strip()},
    "application": {"wasichai": "1.3.0", "app": "full-sample (all starters but agent)", "flywayVersions": flyway},
    "dataset": "database/fixtures/demo-dataset.json, loaded by scripts/seed-demo.mjs",
    "restore": {
        "order": ["create an empty database", "psql -v ON_ERROR_STOP=1 -f database/backups/wasichai-demo.sql", "psql -f database/verify.sql"],
        "script": "scripts/restore-demo-db.sh",
        "requires": ["PostgreSQL 18", "PostGIS 3.6 available to CREATE EXTENSION", "a role allowed to create extensions and schemas"]
    },
    "notIncluded": ["WASICHAI_JWT_SECRET (configuration)", "files of the files module's store (the demo uses none)", "GeoServer state"],
    "rowCounts": rows
}
json.dump(data, open(manifest, 'w'), indent=4, ensure_ascii=False); open(manifest, 'a').write('\n')
PY
echo "export: $OUT ($(wc -c < "$OUT") bytes), manifest $MANIFEST"
