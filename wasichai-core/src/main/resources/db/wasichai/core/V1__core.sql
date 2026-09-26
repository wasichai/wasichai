-- wasichai core schema: the original V1..V14 rebaselined, core tables only (ADR-0026).
-- ${metadataSchema} = metadata + identity, fixed, flyway owns it.
-- ${dataSchema}     = business data, one table per custom object, built at runtime. ADR-004.
-- modules add their own tables, columns and constraints in their own migrations.

CREATE SCHEMA IF NOT EXISTS ${metadataSchema};
CREATE SCHEMA IF NOT EXISTS ${dataSchema};

-- schema-qualified: flyway's search_path puts ${metadataSchema} first, and an unqualified CREATE
-- EXTENSION would land there, gaining 37 extra functions and dying if that schema is ever dropped.
-- public is shared by every app in the database, so pgcrypto (and vector) outlive any one app.
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;

-- pgvector only when the server ships it. nothing in core depends on it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- orgs + identity
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.organizations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text NOT NULL,
    slug       text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ${metadataSchema}.users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    email           text NOT NULL,
    password_hash   text NOT NULL,
    display_name    text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique_per_org UNIQUE (organization_id, email)
);

CREATE INDEX users_email_idx ON ${metadataSchema}.users (lower(email));

-- own_records_only: a role that only sees what its users created (field- and record-level security)
CREATE TABLE ${metadataSchema}.roles (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name             text NOT NULL,
    label            text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    own_records_only boolean NOT NULL DEFAULT false,
    CONSTRAINT roles_name_unique_per_org UNIQUE (organization_id, name)
);

CREATE TABLE ${metadataSchema}.user_roles (
    user_id uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

-- ---------------------------------------------------------------------------
-- metadata: custom objects + fields
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.custom_objects (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    plural_label    text NOT NULL,
    description     text,
    enabled         boolean NOT NULL DEFAULT true,
    physical_table  text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_objects_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT custom_objects_physical_table_unique UNIQUE (physical_table)
);

-- a module that adds a field type re-adds custom_fields_type_valid with its type appended (R4),
-- and adds its own attribute columns after updated_at (R3)
CREATE TABLE ${metadataSchema}.custom_fields (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    object_id                 uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name                      text NOT NULL,
    label                     text NOT NULL,
    type                      text NOT NULL,
    column_name               text NOT NULL,
    required                  boolean NOT NULL DEFAULT false,
    is_unique                 boolean NOT NULL DEFAULT false,
    default_value             text,
    description               text,
    position                  integer NOT NULL DEFAULT 0,
    validation                jsonb,
    enum_options              jsonb,
    relation_target_object_id uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE RESTRICT,
    visible                   boolean NOT NULL DEFAULT true,
    editable                  boolean NOT NULL DEFAULT true,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT custom_fields_name_unique_per_object UNIQUE (object_id, name),
    CONSTRAINT custom_fields_type_valid CHECK (type IN (
        'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME',
        'ENUM', 'EMAIL', 'URL', 'UUID', 'RELATION'
    )),
    CONSTRAINT custom_fields_enum_has_options CHECK (type <> 'ENUM' OR enum_options IS NOT NULL),
    CONSTRAINT custom_fields_relation_has_target CHECK (
        type <> 'RELATION' OR relation_target_object_id IS NOT NULL
    )
);

CREATE INDEX custom_fields_object_idx ON ${metadataSchema}.custom_fields (object_id, position);

-- ---------------------------------------------------------------------------
-- relationships. the FK lives on the source for MANY_TO_ONE and on the target for ONE_TO_MANY,
-- so the field is named relation_field_id. its FK keeps the name postgres gave it before the
-- column was renamed, so the schema stays identical to the original.
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.relationships (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name              text NOT NULL,
    label             text NOT NULL,
    type              text NOT NULL,
    source_object_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    target_object_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    relation_field_id uuid,
    join_table        text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    inverse_label     text,
    CONSTRAINT relationships_source_field_id_fkey FOREIGN KEY (relation_field_id)
        REFERENCES ${metadataSchema}.custom_fields (id) ON DELETE SET NULL,
    CONSTRAINT relationships_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT relationships_type_valid CHECK (type IN (
        'ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY'
    ))
);

CREATE INDEX relationships_source_idx ON ${metadataSchema}.relationships (source_object_id);
CREATE INDEX relationships_target_idx ON ${metadataSchema}.relationships (target_object_id);

-- ---------------------------------------------------------------------------
-- permissions. object/action, plus field-level rules.
-- restriction model: no field rule = full access. a role narrows access, it never widens it.
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.permissions (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id   uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    object_id uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    action    text NOT NULL,
    allowed   boolean NOT NULL DEFAULT true,
    CONSTRAINT permissions_action_valid CHECK (action IN (
        'READ', 'CREATE', 'UPDATE', 'DELETE', 'MANAGE_METADATA', 'MANAGE_ORGANIZATION'
    )),
    CONSTRAINT permissions_unique UNIQUE NULLS NOT DISTINCT (role_id, object_id, action)
);

CREATE TABLE ${metadataSchema}.field_permissions (
    role_id   uuid NOT NULL REFERENCES ${metadataSchema}.roles (id) ON DELETE CASCADE,
    field_id  uuid NOT NULL REFERENCES ${metadataSchema}.custom_fields (id) ON DELETE CASCADE,
    can_read  boolean NOT NULL DEFAULT true,
    can_write boolean NOT NULL DEFAULT true,
    PRIMARY KEY (role_id, field_id)
);

-- dropping a field wipes its rules; the index keeps that cascade cheap
CREATE INDEX field_permissions_field_idx ON ${metadataSchema}.field_permissions (field_id);

-- ---------------------------------------------------------------------------
-- audit log. document_id has no FK here: the module that issues documents adds it, and the
-- ISSUE operation to the CHECK, in its own migration (R10).
-- ---------------------------------------------------------------------------

CREATE TABLE ${metadataSchema}.audit_log (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    user_id         uuid,
    object_name     text NOT NULL,
    record_id       uuid,
    operation       text NOT NULL,
    before_state    jsonb,
    after_state     jsonb,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    document_id     uuid,
    CONSTRAINT audit_log_operation_valid CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE'))
);

CREATE INDEX audit_log_org_time_idx ON ${metadataSchema}.audit_log (organization_id, occurred_at DESC);
CREATE INDEX audit_log_record_idx ON ${metadataSchema}.audit_log (object_name, record_id);
