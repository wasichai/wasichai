-- wasichai-forms: named forms of an object. the original's V1 + V5 rebaselined (ADR-0026): the final shape
-- only, no back-fill, clean start. column order matches the original.

CREATE TABLE ${metadataSchema}.forms (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    CONSTRAINT forms_name_unique_per_object UNIQUE (object_id, name)
);
