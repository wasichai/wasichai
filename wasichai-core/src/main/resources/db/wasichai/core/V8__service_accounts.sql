-- service accounts (ADR-043): a server-to-server caller of one organization. client id + secret for a short token.
-- each account is backed by a users row with the same id, so every column that points at users (user_roles,
-- audit_log.user_id, created_by, automation runs, issued documents, preferences) takes the token's subject as is.
-- that row never signs in: disabled, a random password nobody knows, hidden from /api/users.
-- deleting the users row deletes the account and its roles.
CREATE TABLE ${metadataSchema}.service_accounts (
    id                uuid PRIMARY KEY REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name              text NOT NULL,
    secret_hash       text NOT NULL,
    enabled           boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    secret_rotated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT service_accounts_name_unique_per_org UNIQUE (organization_id, name),
    CONSTRAINT service_accounts_name_valid CHECK (name ~ '^[a-z][a-z0-9_-]{1,48}$')
);
