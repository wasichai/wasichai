-- where a change comes from (ADR-050). correlation_id: the request that started it (X-Correlation-Id, or one the
-- platform generated), kept through an automation run. source: what wrote it, set by code only: api, platform,
-- automation:<rule>, an app's own label (job:retention), app when nothing said. null on rows written before this.
-- the checks are NOT VALID: every existing row is null in both, nothing to scan. new rows are checked.
ALTER TABLE ${metadataSchema}.audit_log
    ADD COLUMN correlation_id text,
    ADD COLUMN source text;
ALTER TABLE ${metadataSchema}.audit_log
    ADD CONSTRAINT audit_log_correlation_id_valid CHECK (correlation_id ~ '^[A-Za-z0-9._-]{1,64}$') NOT VALID,
    ADD CONSTRAINT audit_log_source_valid CHECK (source ~ '^[A-Za-z0-9._:-]{1,64}$') NOT VALID;

-- GET /api/audit?correlationId=: every row of one request, inside the tenant
CREATE INDEX audit_log_correlation_idx ON ${metadataSchema}.audit_log (organization_id, correlation_id);
