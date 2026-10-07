-- organizational units (ADR-045): a tree per organization (gerencia > subgerencia > area) and who sits where.
-- a code never changes, apps bind to it; the label can. the parent key is composite, so a parent is always of the
-- same tenant. no action on it: deleting the organization drops the whole tree, the admin route refuses a unit with
-- children. membership is many-to-many (encargaturas, shared staff) and grants nothing: it is not authorization.
CREATE TABLE ${metadataSchema}.org_units (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    parent_id       uuid,
    code            text NOT NULL,
    label           text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT org_units_code_unique UNIQUE (organization_id, code),
    CONSTRAINT org_units_org_id_unique UNIQUE (organization_id, id),
    CONSTRAINT org_units_code_valid CHECK (code ~ '^[A-Z][A-Z0-9_]{1,48}$'),
    CONSTRAINT org_units_label_valid CHECK (length(label) BETWEEN 1 AND 120),
    CONSTRAINT org_units_not_own_parent CHECK (parent_id <> id),
    CONSTRAINT org_units_parent_fkey FOREIGN KEY (organization_id, parent_id)
        REFERENCES ${metadataSchema}.org_units (organization_id, id)
);
CREATE INDEX org_units_parent_idx ON ${metadataSchema}.org_units (parent_id);

CREATE TABLE ${metadataSchema}.user_org_units (
    user_id uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    unit_id uuid NOT NULL REFERENCES ${metadataSchema}.org_units (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, unit_id)
);
CREATE INDEX user_org_units_unit_idx ON ${metadataSchema}.user_org_units (unit_id);
