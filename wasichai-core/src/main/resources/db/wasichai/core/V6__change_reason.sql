-- change reason (ADR-041). audit_log.reason: why the record changed, as its writer said it. null = none given.
-- custom_objects.requires_reason: no reason, no write. off by default.
ALTER TABLE ${metadataSchema}.audit_log
    ADD COLUMN reason text;
ALTER TABLE ${metadataSchema}.custom_objects
    ADD COLUMN requires_reason boolean NOT NULL DEFAULT false;
