-- sign-in through an external identity provider (oidc mode): a user may have no local password,
-- and is linked to the provider's subject ("sub"). subject is unique within an organization.
ALTER TABLE ${metadataSchema}.users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE ${metadataSchema}.users ADD COLUMN external_subject text;
CREATE UNIQUE INDEX users_org_external_subject_uq
    ON ${metadataSchema}.users (organization_id, external_subject)
    WHERE external_subject IS NOT NULL;
