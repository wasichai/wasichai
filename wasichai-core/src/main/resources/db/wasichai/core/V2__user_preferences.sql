-- per-user ui choices (ADR-034). no row = the defaults. theme ids belong to each app, so no check against a list.
CREATE TABLE ${metadataSchema}.user_preferences (
    user_id    uuid PRIMARY KEY REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    theme      text NOT NULL DEFAULT 'system',
    locale     text,
    updated_at timestamptz NOT NULL DEFAULT now()
);
