-- one line per catalog fact of schema __SCHEMA__, the schema name printed as META. run it with
-- search_path = public so every name in the schema prints qualified, the same way on both sides.
-- extension-owned objects and flyway's history tables are not the schema's own facts.
-- used by SchemaParityTest and generate-expected.sh: change it, then regenerate the fixture.
WITH s AS (
    SELECT oid FROM pg_namespace WHERE nspname = '__SCHEMA__'
),
ext AS (
    SELECT objid FROM pg_depend WHERE deptype = 'e'
),
rel AS (
    SELECT c.oid, c.relname
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s)
      AND c.relkind IN ('r', 'p')
      AND c.relname NOT LIKE 'flyway\_%'
      AND c.oid NOT IN (SELECT objid FROM ext)
),
facts AS (
    SELECT 'table ' || r.relname AS line
    FROM rel r
    UNION ALL
    SELECT format('column %s.%s #%s %s%s%s', r.relname, a.attname,
                  row_number() OVER (PARTITION BY r.oid ORDER BY a.attnum),
                  format_type(a.atttypid, a.atttypmod),
                  CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                  COALESCE(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), ''))
    FROM rel r
    JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
    LEFT JOIN pg_attrdef d ON d.adrelid = r.oid AND d.adnum = a.attnum
    UNION ALL
    SELECT format('constraint %s.%s %s', r.relname, con.conname, pg_get_constraintdef(con.oid))
    FROM rel r
    JOIN pg_constraint con ON con.conrelid = r.oid
    UNION ALL
    SELECT format('index %s.%s %s', r.relname, i.relname, pg_get_indexdef(i.oid))
    FROM rel r
    JOIN pg_index x ON x.indrelid = r.oid
    JOIN pg_class i ON i.oid = x.indexrelid
    UNION ALL
    SELECT format('trigger %s.%s %s', r.relname, t.tgname, pg_get_triggerdef(t.oid))
    FROM rel r
    JOIN pg_trigger t ON t.tgrelid = r.oid AND NOT t.tgisinternal
    UNION ALL
    SELECT format('function %s(%s) returns %s body %s', p.proname, pg_get_function_identity_arguments(p.oid), pg_get_function_result(p.oid),
                  md5(replace(p.prosrc, '__SCHEMA__.', 'META.')))
    FROM pg_proc p
    WHERE p.pronamespace = (SELECT oid FROM s) AND p.oid NOT IN (SELECT objid FROM ext)
    UNION ALL
    SELECT format('view %s body %s', c.relname, md5(replace(pg_get_viewdef(c.oid), '__SCHEMA__.', 'META.')))
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s) AND c.relkind IN ('v', 'm') AND c.oid NOT IN (SELECT objid FROM ext)
    UNION ALL
    SELECT 'sequence ' || c.relname
    FROM pg_class c
    WHERE c.relnamespace = (SELECT oid FROM s) AND c.relkind = 'S' AND c.oid NOT IN (SELECT objid FROM ext)
)
SELECT replace(line, '__SCHEMA__.', 'META.') AS line
FROM facts
ORDER BY line;
