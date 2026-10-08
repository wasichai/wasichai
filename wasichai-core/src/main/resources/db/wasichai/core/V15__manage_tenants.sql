-- MANAGE_TENANTS (ADR-055): creating and deleting tenants, apart from administering one. a built-in action,
-- granted with no object only. the generated declared_object_id keeps its six names: an object-less row is
-- null there anyway, so the declared-action foreign key never binds it.
-- an object that already declared an action of this name (ADR-042) stops this migration: rename it first.
ALTER TABLE ${metadataSchema}.permissions DROP CONSTRAINT permissions_action_valid;

ALTER TABLE ${metadataSchema}.permissions
    ADD CONSTRAINT permissions_action_valid CHECK (
        action IN ('READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION', 'MANAGE_TENANTS')
        OR object_id IS NOT NULL
    ),
    ADD CONSTRAINT permissions_tenants_no_object CHECK (action <> 'MANAGE_TENANTS' OR object_id IS NULL);

ALTER TABLE ${metadataSchema}.object_actions DROP CONSTRAINT object_actions_not_builtin;

ALTER TABLE ${metadataSchema}.object_actions
    ADD CONSTRAINT object_actions_not_builtin CHECK (name NOT IN (
        'READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION', 'MANAGE_TENANTS'
    ));
