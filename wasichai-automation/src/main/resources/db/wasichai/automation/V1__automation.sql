-- wasichai-automation: trigger -> conditions -> actions, defined as metadata like everything else.
-- the original's V8 rebaselined (ADR-0026). a run row is the audit trail of one automation meeting one
-- record change. matching happens when the change lands (against the snapshot); the actions run
-- later, off the request.

CREATE TABLE ${metadataSchema}.automations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    definition jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT automations_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE INDEX automations_object_idx ON ${metadataSchema}.automations (organization_id, object_id);

CREATE TABLE ${metadataSchema}.automation_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    automation_id uuid NOT NULL REFERENCES ${metadataSchema}.automations (id) ON DELETE CASCADE,
    object_name text NOT NULL,
    record_id uuid,
    trigger_type text NOT NULL,
    -- PENDING -> RUNNING -> SUCCEEDED | FAILED, or SKIPPED before it ever runs
    status text NOT NULL,
    -- how many automations deep this chain already is. the loop guard reads it.
    depth integer NOT NULL DEFAULT 0,
    -- the record as it was when the change landed. conditions and templates read this, not the
    -- live row: re-reading later would judge a change that may have moved on since.
    payload jsonb NOT NULL,
    steps jsonb,
    error text,
    attempts integer NOT NULL DEFAULT 0,
    -- who caused the change. the actions themselves run as the platform, not as this user.
    user_id uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz
);

-- the drain query. partial index: only pending rows are ever claimed.
CREATE INDEX automation_runs_pending_idx ON ${metadataSchema}.automation_runs (created_at) WHERE status = 'PENDING';
CREATE INDEX automation_runs_log_idx ON ${metadataSchema}.automation_runs (organization_id, created_at DESC);
CREATE INDEX automation_runs_automation_idx ON ${metadataSchema}.automation_runs (automation_id, created_at DESC);
