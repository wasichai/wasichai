-- integrity checks of a demo database, after a restore or a seed. read-only.
-- usage: psql -v ON_ERROR_STOP=1 -f docs/manuals/database/verify.sql   (any failed check raises and stops)
-- row counts against the manifest are compared by scripts/restore-demo-db.sh, not here.
\set QUIET on
\pset tuples_only on

DO $$
DECLARE
    r record;
    n bigint;
    problems int := 0;
BEGIN
    -- 1. the extensions the platform needs
    FOR r IN SELECT unnest(ARRAY['pgcrypto', 'postgis']) AS ext LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = r.ext) THEN
            RAISE WARNING 'missing extension %', r.ext;
            problems := problems + 1;
        END IF;
    END LOOP;

    -- 2. the audit log stays append-only (ADR-054): its triggers came back with the restore
    FOR r IN SELECT unnest(ARRAY['audit_log_append_only', 'audit_log_no_truncate']) AS trg LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = r.trg AND tgrelid = 'wasichai.audit_log'::regclass) THEN
            RAISE WARNING 'missing trigger % on wasichai.audit_log', r.trg;
            problems := problems + 1;
        END IF;
    END LOOP;

    -- 3. every object has its physical table, and the table has the object's columns' rows
    FOR r IN SELECT name, physical_table FROM wasichai.custom_objects LOOP
        IF to_regclass(format('app_data.%I', r.physical_table)) IS NULL THEN
            RAISE WARNING 'object % has no table app_data.%', r.name, r.physical_table;
            problems := problems + 1;
        END IF;
    END LOOP;

    -- 4. no orphan behind any foreign key of either schema, metadata or record tables (RELATION fields are real keys)
    FOR r IN
        SELECT c.conname, c.conrelid::regclass AS child, c.confrelid::regclass AS parent,
               (SELECT string_agg(format('ch.%I', a.attname), ', ' ORDER BY k.ord)
                FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord) JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS ccols,
               (SELECT string_agg(format('pa.%I', a.attname), ', ' ORDER BY k.ord)
                FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord) JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum) AS pcols,
               (SELECT string_agg(format('ch.%I IS NOT NULL', a.attname), ' AND ')
                FROM unnest(c.conkey) k(attnum) JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS notnull
        FROM pg_constraint c JOIN pg_namespace ns ON ns.oid = c.connamespace
        WHERE c.contype = 'f' AND ns.nspname IN ('wasichai', 'app_data')
    LOOP
        EXECUTE format('SELECT count(*) FROM %s ch WHERE %s AND NOT EXISTS (SELECT 1 FROM %s pa WHERE (%s) = (%s))',
                       r.child, r.notnull, r.parent, r.pcols, r.ccols) INTO n;
        IF n > 0 THEN
            RAISE WARNING '% rows of % break %', n, r.child, r.conname;
            problems := problems + 1;
        END IF;
    END LOOP;

    -- 5. every sequence is past the values it already gave out, so the next insert does not collide
    FOR r IN
        SELECT s.oid::regclass AS seq, t.oid::regclass AS tbl, a.attname AS col
        FROM pg_class s
        JOIN pg_depend d ON d.objid = s.oid AND d.deptype IN ('a', 'i')
        JOIN pg_class t ON t.oid = d.refobjid
        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = d.refobjsubid
        JOIN pg_namespace ns ON ns.oid = t.relnamespace
        WHERE s.relkind = 'S' AND ns.nspname IN ('wasichai', 'app_data')
    LOOP
        EXECUTE format('SELECT coalesce(max(%I), 0) FROM %s', r.col, r.tbl) INTO n;
        IF n > (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM pg_sequences ps
                WHERE format('%I.%I', ps.schemaname, ps.sequencename)::regclass = r.seq) THEN
            RAISE WARNING 'sequence % is behind %.% (max %)', r.seq, r.tbl, r.col, n;
            problems := problems + 1;
        END IF;
    END LOOP;

    -- 6. each geometry column carries the srid its field declares
    FOR r IN
        SELECT o.name AS object, f.name AS field, f.srid, g.srid AS column_srid
        FROM wasichai.custom_fields f
        JOIN wasichai.custom_objects o ON o.id = f.object_id
        LEFT JOIN public.geometry_columns g ON g.f_table_schema = 'app_data' AND g.f_table_name = o.physical_table AND g.f_geometry_column = f.name
        WHERE f.type = 'GEOMETRY'
    LOOP
        IF r.column_srid IS DISTINCT FROM r.srid THEN
            RAISE WARNING 'geometry %.% declares srid %, column has %', r.object, r.field, r.srid, r.column_srid;
            problems := problems + 1;
        END IF;
    END LOOP;

    IF problems > 0 THEN
        RAISE EXCEPTION 'verify: % problem(s), see the warnings above', problems;
    END IF;
    RAISE NOTICE 'verify: extensions, audit triggers, tables, foreign keys, sequences and srids OK';
END
$$;

-- a short picture of the demo, for the person reading the output
SELECT format('%-28s %s', 'organizations', count(*)) FROM wasichai.organizations
UNION ALL SELECT format('%-28s %s', 'users (people)', count(*)) FROM wasichai.users u
    WHERE NOT EXISTS (SELECT 1 FROM wasichai.service_accounts s WHERE s.id = u.id)
UNION ALL SELECT format('%-28s %s', 'objects', count(*)) FROM wasichai.custom_objects
UNION ALL SELECT format('%-28s %s', 'audit entries', count(*)) FROM wasichai.audit_log;
