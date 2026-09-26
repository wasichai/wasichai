-- wasichai-workflow: states and transitions, no BPM engine. the original's V7 rebaselined (ADR-0026).
-- the definition lives here; the record's current state lives in a real column on the physical
-- table (workflow_state), added by core's ObjectSchemaManager when a workflow is attached.

CREATE TABLE ${metadataSchema}.workflows (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    definition jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workflows_one_per_object UNIQUE (object_id),
    CONSTRAINT workflows_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE INDEX workflows_org_idx ON ${metadataSchema}.workflows (organization_id);
