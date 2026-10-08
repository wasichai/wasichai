-- the correlation id of the change that queued the run (ADR-050). the run is drained later, off the request, so its
-- writes take the id from here: one request's rows stay together in audit_log. null on runs queued before this.
ALTER TABLE ${metadataSchema}.automation_runs
    ADD COLUMN correlation_id text;
