-- a test-only module adding a field type the way wasichai-gis does: its own custom_fields column (R3)
-- and the type check re-added with its type appended (R4).
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN unit text;

ALTER TABLE ${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;
ALTER TABLE ${metadataSchema}.custom_fields ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (
    'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
    'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'MEASURE'
));

ALTER TABLE ${metadataSchema}.custom_fields
    ADD CONSTRAINT custom_fields_measure_has_unit CHECK (type <> 'MEASURE' OR unit IS NOT NULL);
