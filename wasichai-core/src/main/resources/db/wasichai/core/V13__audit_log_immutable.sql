-- audit_log is append-only in the database, not only by convention of AuditService (ADR-054). every UPDATE, DELETE and
-- TRUNCATE is refused, for every role, the owner included. the owner can still drop or disable the triggers: that is
-- why the guide asks for a migration role apart from the runtime role.
--
-- two holes, on purpose:
--   - a foreign-key action that only nulls document_id (wasichai-documents' audit_log_document_id_fkey, ON DELETE SET
--     NULL): the row keeps every other column, and the update runs inside the action's trigger (depth > 1), never as
--     a statement of its own.
--   - purge: DELETE or TRUNCATE in a transaction that ran SET LOCAL wasichai.audit.purge = 'on', from a session that
--     logged in as the role wasichai.audit.purge-role names (audit_log_purge_role(), R__audit_purge_role.sql).
--     session_user, not current_user: SET ROLE and SECURITY DEFINER change current_user, a login does not.
-- idempotent: CREATE OR REPLACE, DROP TRIGGER IF EXISTS.
CREATE OR REPLACE FUNCTION ${metadataSchema}.audit_log_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF pg_trigger_depth() > 1
            AND OLD.document_id IS NOT NULL AND NEW.document_id IS NULL
            AND (to_jsonb(OLD) - 'document_id') = (to_jsonb(NEW) - 'document_id') THEN
            RETURN NEW;
        END IF;
    ELSIF current_setting('wasichai.audit.purge', true) = 'on'
        AND session_user = ${metadataSchema}.audit_log_purge_role() THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege',
              HINT = 'audit entries are never changed nor removed (ADR-054); a purge needs the role wasichai.audit.purge-role names';
END
$$;

DROP TRIGGER IF EXISTS audit_log_append_only ON ${metadataSchema}.audit_log;
CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON ${metadataSchema}.audit_log
    FOR EACH ROW EXECUTE FUNCTION ${metadataSchema}.audit_log_guard();

DROP TRIGGER IF EXISTS audit_log_no_truncate ON ${metadataSchema}.audit_log;
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON ${metadataSchema}.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION ${metadataSchema}.audit_log_guard();
