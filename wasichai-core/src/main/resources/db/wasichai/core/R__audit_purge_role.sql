-- the role that may purge audit_log (ADR-054): wasichai.audit.purge-role, empty for none, so no purge. repeatable:
-- flyway checksums it with the placeholder replaced, so it runs again whenever the property changes. only a role
-- that owns the function (the migration role) can replace it; the runtime role cannot name itself.
CREATE OR REPLACE FUNCTION ${metadataSchema}.audit_log_purge_role() RETURNS text
    LANGUAGE sql
    STABLE
AS $$ SELECT nullif('${auditPurgeRole}', '') $$;
