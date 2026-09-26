-- wasichai-pages: record pages. the original's V1 + V4 + V11 rebaselined (ADR-0026): final shape
-- only. a page's arrangement is its template column; the V10 tree rewrite and V11 delete were
-- data steps with nothing to do on a clean start.

CREATE TABLE ${metadataSchema}.pages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    kind            text NOT NULL DEFAULT 'RECORD_DETAIL',
    -- no CHECK on template: the catalogue is code, and a CHECK would make adding one a migration
    template        text NOT NULL DEFAULT 'one-region',
    CONSTRAINT pages_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT pages_kind_valid CHECK (kind IN ('RECORD_DETAIL')),
    CONSTRAINT pages_kind_unique_per_object UNIQUE (object_id, kind)
);
