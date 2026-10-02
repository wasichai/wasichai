-- app-declared actions (ADR-042): verbs an object has beyond CRUD, say ANULAR_AJENO on recibo.
-- granted through permissions like the built-in ones, always on the object that declares them.
CREATE TABLE ${metadataSchema}.object_actions (
    object_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name       text NOT NULL,
    label      text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (object_id, name),
    CONSTRAINT object_actions_name_valid CHECK (name ~ '^[A-Z][A-Z0-9_]{1,48}$'),
    CONSTRAINT object_actions_not_builtin CHECK (name NOT IN (
        'READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION'
    ))
);

-- a built-in action is any action, anywhere. any other one must be declared by the object it names:
-- the generated column is that object for a declared action and null for a built-in one, so the
-- foreign key only binds declared grants, and removing a declaration cascades its grants away.
-- STORED: postgres 18 defaults to virtual, which a foreign key cannot use.
ALTER TABLE ${metadataSchema}.permissions DROP CONSTRAINT permissions_action_valid;

ALTER TABLE ${metadataSchema}.permissions
    ADD COLUMN declared_object_id uuid GENERATED ALWAYS AS (
        CASE WHEN action IN ('READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION')
            THEN NULL ELSE object_id END
    ) STORED;

ALTER TABLE ${metadataSchema}.permissions
    ADD CONSTRAINT permissions_action_valid CHECK (
        action IN ('READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION')
        OR object_id IS NOT NULL
    ),
    ADD CONSTRAINT permissions_declared_action_fkey FOREIGN KEY (declared_object_id, action)
        REFERENCES ${metadataSchema}.object_actions (object_id, name) ON DELETE CASCADE;
