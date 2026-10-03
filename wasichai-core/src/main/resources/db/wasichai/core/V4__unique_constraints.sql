-- declared composite uniques (ADR-037). an object lists them as field names in constraint order, e.g.
-- [["sistema_origen", "referencia_externa"]]. the constraints themselves live on each organization's
-- data table and are built by ObjectSchemaManager, never here.
ALTER TABLE ${metadataSchema}.custom_objects ADD COLUMN unique_constraints jsonb NOT NULL DEFAULT '[]'::jsonb;
