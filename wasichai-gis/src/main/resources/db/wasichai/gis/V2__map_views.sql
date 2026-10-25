-- wasichai-gis: named map views of an object. which geometry it draws and how it colours it.
-- scoped to the organization so two tenants can use the same names, gone with the object.

CREATE TABLE ${metadataSchema}.map_views (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    geometry_name   text NOT NULL,
    style           jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT map_views_name_unique_per_object UNIQUE (object_id, name)
);

CREATE INDEX map_views_organization ON ${metadataSchema}.map_views (organization_id);
