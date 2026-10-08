-- wasichai-notifications, delivery channels (ADR-060). the inbox matches its audience on read; a channel
-- outside the app (email) needs names, so news is fanned out to people when it is written, one row per
-- person and channel, and a worker sends it later. the write never waits on the channel.

CREATE TABLE ${metadataSchema}.notification_deliveries (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id uuid NOT NULL REFERENCES ${metadataSchema}.notifications (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    channel         text NOT NULL,
    status          text NOT NULL DEFAULT 'PENDING',
    attempts        integer NOT NULL DEFAULT 0,
    last_error      text,
    -- a scheduled notification goes out when it is published, a failed attempt after its backoff
    next_attempt_at timestamptz NOT NULL,
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_deliveries_status_valid CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    -- news again (reopened, kind changed) resets the row: one person gets one copy per news
    CONSTRAINT notification_deliveries_unique UNIQUE (notification_id, user_id, channel)
);
CREATE INDEX notification_deliveries_due_idx ON ${metadataSchema}.notification_deliveries (organization_id, next_attempt_at)
    WHERE status = 'PENDING';
CREATE INDEX notification_deliveries_user_idx ON ${metadataSchema}.notification_deliveries (user_id);

-- which kinds a person wants on a channel. no row = every kind. the in-app inbox is always on.
CREATE TABLE ${metadataSchema}.notification_preferences (
    user_id    uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    channel    text NOT NULL,
    kinds      text[] NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, channel),
    CONSTRAINT notification_preferences_kinds_valid CHECK (kinds <@ ARRAY['INFO', 'WARNING', 'ACTION']::text[])
);
