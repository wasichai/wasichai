-- per-language texts of objects, fields and relationships (issue 155, ADR-078): property -> language -> text, and for
-- an enum field's options option -> language -> text. the single-language columns keep their meaning; a row stored
-- before reads {}. nothing checks the keys here: the service does, as it does for workflow labels (ADR-070)
ALTER TABLE ${metadataSchema}.custom_objects ADD COLUMN translations jsonb NOT NULL DEFAULT '{}';
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN translations jsonb NOT NULL DEFAULT '{}';
ALTER TABLE ${metadataSchema}.relationships ADD COLUMN translations jsonb NOT NULL DEFAULT '{}';
