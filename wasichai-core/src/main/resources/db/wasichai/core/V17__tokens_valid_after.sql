-- token revocation (ADR-059): a token whose iat is before its user's marker is refused, when
-- wasichai.security.jwt.revocation is on. null: nothing revoked yet. always a whole second, since iat is one.
-- a service account's marker is its backing user's (ADR-043).
ALTER TABLE ${metadataSchema}.users ADD COLUMN tokens_valid_after timestamptz;
