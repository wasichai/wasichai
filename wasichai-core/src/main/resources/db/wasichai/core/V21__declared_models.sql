-- the app's declared model that owns an object or a relationship (issue 165, ADR-081): the platform ensures it and
-- nobody else changes it. null for what a person made, which is everything stored before
ALTER TABLE ${metadataSchema}.custom_objects ADD COLUMN declared_by text
    CONSTRAINT custom_objects_declared_by_valid CHECK (declared_by ~ '^[a-z][a-z0-9_-]{0,48}$');
ALTER TABLE ${metadataSchema}.relationships ADD COLUMN declared_by text
    CONSTRAINT relationships_declared_by_valid CHECK (declared_by ~ '^[a-z][a-z0-9_-]{0,48}$');
