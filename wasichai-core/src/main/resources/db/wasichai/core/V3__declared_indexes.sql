-- declared indexes (ADR-036). a field may ask for its own index; an object lists composite ones as
-- field names in index order, e.g. [["anio", "predio"]]. the indexes themselves live on each
-- organization's data table and are built by ObjectSchemaManager, never here.
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN indexed boolean NOT NULL DEFAULT false;
ALTER TABLE ${metadataSchema}.custom_objects ADD COLUMN indexes jsonb NOT NULL DEFAULT '[]'::jsonb;
