-- what a record write of an object leaves in audit_log, and the fields whose values stay out of it (issue 164,
-- ADR-079). FULL and true are what every row did before. the values are the service's AuditMode
ALTER TABLE ${metadataSchema}.custom_objects ADD COLUMN audit_mode text NOT NULL DEFAULT 'FULL'
    CHECK (audit_mode IN ('FULL', 'OPERATION', 'NONE'));
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN audited boolean NOT NULL DEFAULT true;
