-- wasichai-files: FILE and IMAGE in core's custom_fields_type_valid (R4), appended to whatever list it
-- holds, so another module's type (gis's GEOMETRY) stays. a flyway callback, run after every migrate of
-- this module: a module that rebuilds the list with its own type later (gis V1 does) drops these two, and
-- the next start puts them back. reads the catalog only when nothing is missing.

DO $$
DECLARE
    current_def text;
    known text[];
BEGIN
    SELECT pg_get_constraintdef(c.oid) INTO current_def
    FROM pg_constraint c
    WHERE c.conrelid = '${metadataSchema}.custom_fields'::regclass AND c.conname = 'custom_fields_type_valid';
    IF current_def IS NULL THEN
        RAISE EXCEPTION 'custom_fields_type_valid is missing';
    END IF;
    SELECT array_agg(m.literal[1] ORDER BY m.n) INTO known
    FROM regexp_matches(current_def, '''([A-Z][A-Z0-9_]*)''', 'g') WITH ORDINALITY AS m(literal, n);
    IF 'FILE' = ANY (known) AND 'IMAGE' = ANY (known) THEN
        RETURN;
    END IF;
    IF NOT 'FILE' = ANY (known) THEN
        known := array_append(known, 'FILE');
    END IF;
    IF NOT 'IMAGE' = ANY (known) THEN
        known := array_append(known, 'IMAGE');
    END IF;
    EXECUTE 'ALTER TABLE ${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid';
    EXECUTE format(
        'ALTER TABLE ${metadataSchema}.custom_fields ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (%s))',
        (SELECT string_agg(quote_literal(t), ', ' ORDER BY n) FROM unnest(known) WITH ORDINALITY AS u(t, n))
    );
END
$$;
