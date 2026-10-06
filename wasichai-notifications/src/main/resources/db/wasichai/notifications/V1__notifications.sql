-- wasichai-notifications (ADR-046): what people must know or do. the audience is matched when the inbox
-- is read, not copied per person; per-person state lives in receipts. a keyed source upserts by
-- (organization, source, key) and resolves what it no longer reports.

CREATE TABLE ${metadataSchema}.notifications (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    kind            text NOT NULL,
    title           text NOT NULL,
    body            text,
    link            jsonb,
    -- the object a RECORD link opens: the inbox drops the link for a reader without READ on it; gone with the object
    link_object_id  uuid REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE SET NULL,
    publish_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz,
    due_at          timestamptz,
    source          text NOT NULL,
    source_key      text,
    fingerprint     text NOT NULL,
    resolved_at     timestamptz,
    created_by      uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notifications_kind_valid CHECK (kind IN ('INFO', 'WARNING', 'ACTION')),
    CONSTRAINT notifications_window_valid CHECK (expires_at IS NULL OR expires_at > publish_at),
    CONSTRAINT notifications_source_key_unique UNIQUE (organization_id, source, source_key)
);
CREATE INDEX notifications_open_idx ON ${metadataSchema}.notifications (organization_id, publish_at DESC)
    WHERE resolved_at IS NULL;
CREATE INDEX notifications_source_idx ON ${metadataSchema}.notifications (organization_id, source)
    WHERE resolved_at IS NULL;
CREATE INDEX notifications_link_object_idx ON ${metadataSchema}.notifications (link_object_id)
    WHERE link_object_id IS NOT NULL;

CREATE TABLE ${metadataSchema}.notification_targets (
    notification_id uuid NOT NULL REFERENCES ${metadataSchema}.notifications (id) ON DELETE CASCADE,
    type            text NOT NULL,
    user_id         uuid REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    role_name       text,
    unit_id         uuid REFERENCES ${metadataSchema}.org_units (id) ON DELETE CASCADE,
    CONSTRAINT notification_targets_type_valid CHECK (type IN ('ALL', 'USER', 'ROLE', 'UNIT')),
    CONSTRAINT notification_targets_shape CHECK (
        ((type = 'USER') = (user_id IS NOT NULL))
        AND ((type = 'ROLE') = (role_name IS NOT NULL))
        AND ((type = 'UNIT') = (unit_id IS NOT NULL))
    )
);
CREATE INDEX notification_targets_notification_idx ON ${metadataSchema}.notification_targets (notification_id);
CREATE INDEX notification_targets_user_idx ON ${metadataSchema}.notification_targets (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX notification_targets_unit_idx ON ${metadataSchema}.notification_targets (unit_id) WHERE unit_id IS NOT NULL;

-- one row per person who did something with a notification. no row = unread.
CREATE TABLE ${metadataSchema}.notification_receipts (
    notification_id uuid NOT NULL REFERENCES ${metadataSchema}.notifications (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    read_at         timestamptz,
    dismissed_at    timestamptz,
    snoozed_until   timestamptz,
    PRIMARY KEY (notification_id, user_id)
);
CREATE INDEX notification_receipts_user_idx ON ${metadataSchema}.notification_receipts (user_id);

CREATE TABLE ${metadataSchema}.notification_rules (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id       uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name            text NOT NULL,
    label           text NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    definition      jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_rules_name_unique UNIQUE (organization_id, name),
    CONSTRAINT notification_rules_name_valid CHECK (name ~ '^[a-z][a-z0-9_]{1,48}$')
);
CREATE INDEX notification_rules_object_idx ON ${metadataSchema}.notification_rules (organization_id, object_id);

-- when each scheduled source last ran: N replicas run it once per interval, not N times
CREATE TABLE ${metadataSchema}.notification_source_runs (
    source      text PRIMARY KEY,
    last_run_at timestamptz NOT NULL
);
