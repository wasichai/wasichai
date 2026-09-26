-- wasichai-gis: postgis and the GEOMETRY field type (ADR-0027). the original's V1 (extension) + V9 (geometry
-- is a field) rebaselined (ADR-0026): final shape only. the attribute columns are added last, as V9
-- did, so custom_fields ends with the original column order.

-- schema-qualified: flyway's search_path puts ${metadataSchema} first, and an unqualified CREATE
-- EXTENSION would land there. public is shared by every app in the database.
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

ALTER TABLE ${metadataSchema}.custom_fields
    ADD COLUMN geometry_type text,
    ADD COLUMN srid          integer,
    ADD COLUMN dimension     integer;

-- core's check lists its twelve types. GEOMETRY is appended under the same name (P1 R4). no IF
-- EXISTS: if core's constraint is not there, something is wrong and this must fail.
ALTER TABLE ${metadataSchema}.custom_fields DROP CONSTRAINT custom_fields_type_valid;
ALTER TABLE ${metadataSchema}.custom_fields ADD CONSTRAINT custom_fields_type_valid CHECK (type IN (
    'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
    'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION', 'GEOMETRY'
));

-- same shape as the enum and relation checks: metadata a type needs, and only that type.
ALTER TABLE ${metadataSchema}.custom_fields
    ADD CONSTRAINT custom_fields_geometry_has_type CHECK (
        type <> 'GEOMETRY' OR (geometry_type IS NOT NULL AND srid IS NOT NULL AND dimension IS NOT NULL)
    ),
    ADD CONSTRAINT custom_fields_geometry_type_valid CHECK (geometry_type IS NULL OR geometry_type IN (
        'POINT', 'LINESTRING', 'POLYGON', 'MULTIPOINT', 'MULTILINESTRING', 'MULTIPOLYGON'
    )),
    ADD CONSTRAINT custom_fields_dimension_valid CHECK (dimension IS NULL OR dimension IN (2, 3));
