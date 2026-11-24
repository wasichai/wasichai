-- wasichai-agent (ADR-087): the assistant's conversations, kept per user. a conversation belongs to the one user who
-- started it, inside one organization; nobody else reads it, administrators included. archived, not deleted: the
-- history of what was asked is the organization's record of what left for the model.

CREATE TABLE ${metadataSchema}.agent_conversations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    title           text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    archived_at     timestamptz
);
CREATE INDEX agent_conversations_owner_idx ON ${metadataSchema}.agent_conversations (organization_id, user_id, updated_at DESC)
    WHERE archived_at IS NULL;

-- one row per turn side. steps and usage only on the assistant's side; model is the one that answered.
CREATE TABLE ${metadataSchema}.agent_messages (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES ${metadataSchema}.agent_conversations (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    role            text NOT NULL,
    content         text NOT NULL,
    steps           jsonb,
    usage           jsonb,
    model           text,
    truncated       boolean NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT agent_messages_role_valid CHECK (role IN ('USER', 'ASSISTANT'))
);
CREATE INDEX agent_messages_conversation_idx ON ${metadataSchema}.agent_messages (conversation_id, created_at);

-- the model a user picked for the assistant. no row = the app's default.
CREATE TABLE ${metadataSchema}.agent_user_settings (
    user_id         uuid PRIMARY KEY REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    model           text,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
