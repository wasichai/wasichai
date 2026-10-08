-- Idempotency-Key on record creation (ADR-058). one row per organization, caller and key: the answer the first
-- request got, written in the same transaction as the record, so neither exists without the other. user_id null
-- is the platform (RecordService.asPlatform), one caller like any other: NULLS NOT DISTINCT. a service account is
-- a users row (ADR-043). expired rows are deleted by created_at (wasichai.idempotency.ttl).
CREATE TABLE ${metadataSchema}.idempotency_keys (
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    user_id         uuid REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    key             text NOT NULL,
    request_hash    text NOT NULL,
    response_status integer NOT NULL,
    response_body   text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_keys_unique UNIQUE NULLS NOT DISTINCT (organization_id, user_id, key),
    CONSTRAINT idempotency_keys_key_valid CHECK (length(key) BETWEEN 1 AND 128)
);

CREATE INDEX idempotency_keys_created_idx ON ${metadataSchema}.idempotency_keys (created_at);
