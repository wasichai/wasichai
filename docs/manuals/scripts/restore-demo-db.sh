#!/usr/bin/env bash
# restores the demo dump into a demo database, then checks it: verify.sql plus the manifest's row counts.
# usage: docs/manuals/scripts/restore-demo-db.sh [--replace]
#   WASICHAI_DB_HOST/PORT/NAME/USERNAME/PASSWORD   the target, as the app reads them; NAME must end in _demo or _demo_restore
#   --replace   drop and recreate the target first. destroys everything in it: only for a disposable demo database
# the role needs CREATEDB (to create the target) and the right to CREATE EXTENSION postgis, pgcrypto
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUMP="$ROOT/database/backups/wasichai-demo.sql"
MANIFEST="$ROOT/database/manifests/demo-manifest.json"
REPLACE=false
[[ "${1:-}" == "--replace" ]] && REPLACE=true
DB="${WASICHAI_DB_NAME:-}"
[[ "$DB" == *_demo || "$DB" == *_demo_restore ]] || {
    echo "restore: WASICHAI_DB_NAME must name a demo database (*_demo or *_demo_restore), got '${DB}'" >&2
    exit 1
}
export PGHOST="${WASICHAI_DB_HOST:-localhost}" PGPORT="${WASICHAI_DB_PORT:-5432}" PGUSER="${WASICHAI_DB_USERNAME:-wasichai}"
export PGPASSWORD="${WASICHAI_DB_PASSWORD:-wasichai}"

# the dump must be the one the manifest describes
expected=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['sha256'])" "$MANIFEST")
actual=$(sha256sum "$DUMP" | cut -d' ' -f1)
[[ "$expected" == "$actual" ]] || { echo "restore: $DUMP does not match its manifest (sha256)" >&2; exit 1; }

exists=$(psql -XtAd postgres -c "select 1 from pg_database where datname = '$DB'")
if [[ "$exists" == 1 ]]; then
    tables=$(psql -XtAd "$DB" -c "select count(*) from pg_tables where schemaname in ('wasichai', 'app_data')")
    if [[ "$tables" != 0 && "$REPLACE" != true ]]; then
        echo "restore: $DB already holds wasichai tables; rerun with --replace to drop it (destroys its data)" >&2
        exit 1
    fi
    if [[ "$REPLACE" == true ]]; then
        echo "restore: dropping $DB (--replace)"
        psql -XqAd postgres -c "drop database \"$DB\" with (force)"
        exists=""
    fi
fi
[[ "$exists" == 1 ]] || psql -XqAd postgres -c "create database \"$DB\" encoding 'UTF8' template template0"

echo "restore: loading $DUMP into $DB"
psql -XqAd "$DB" -v ON_ERROR_STOP=1 -f "$DUMP" > /dev/null
echo "restore: checking"
PGDATABASE="$DB" psql -XAq -v ON_ERROR_STOP=1 -f "$ROOT/database/verify.sql"

# every table holds the rows it held when exported
psql -XtA -F $'\t' -d "$DB" <<'SQL' > /tmp/wasichai-restore-counts.$$
select table_schema || '.' || table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
from information_schema.tables
where table_schema in ('wasichai', 'app_data') and table_type = 'BASE TABLE'
SQL
python3 - "$MANIFEST" /tmp/wasichai-restore-counts.$$ <<'PY'
import json, sys
want = json.load(open(sys.argv[1]))['rowCounts']
got = dict(line.rstrip('\n').split('\t') for line in open(sys.argv[2]) if line.strip())
got = {k: int(v) for k, v in got.items()}
bad = [f"{t}: manifest {want.get(t)}, restored {got.get(t)}" for t in sorted(set(want) | set(got)) if want.get(t) != got.get(t)]
if bad:
    print("restore: row counts differ\n  " + "\n  ".join(bad), file=sys.stderr); sys.exit(1)
print(f"restore: row counts match the manifest ({len(got)} tables, {sum(got.values())} rows)")
PY
rm -f /tmp/wasichai-restore-counts.$$
echo "restore: $DB is ready; start the app against it (WASICHAI_DB_NAME=$DB)"
