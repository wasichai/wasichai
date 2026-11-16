-- roles an app declares in its model (declared_by = the model's name, null = a person made it), and service
-- accounts that live in the identity provider's realm: no secret here, the provider's client id instead.
-- an account has one credential kind, never both and never none.
ALTER TABLE ${metadataSchema}.roles ADD COLUMN declared_by text;
ALTER TABLE ${metadataSchema}.roles
    ADD CONSTRAINT roles_declared_by_valid CHECK (declared_by ~ '^[a-z][a-z0-9_-]{0,48}$');

ALTER TABLE ${metadataSchema}.service_accounts ALTER COLUMN secret_hash DROP NOT NULL;
ALTER TABLE ${metadataSchema}.service_accounts ADD COLUMN external_client_id text;
ALTER TABLE ${metadataSchema}.service_accounts
    ADD CONSTRAINT service_accounts_one_credential CHECK ((secret_hash IS NULL) <> (external_client_id IS NULL));
CREATE UNIQUE INDEX service_accounts_org_external_client_uq
    ON ${metadataSchema}.service_accounts (organization_id, external_client_id)
    WHERE external_client_id IS NOT NULL;
