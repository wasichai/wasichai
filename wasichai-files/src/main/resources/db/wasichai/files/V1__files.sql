-- wasichai-files: the FILE and IMAGE field types (ADR-0061). a field's column holds the id of a
-- stored_files row; the bytes live in a FileStore under object_key, never in the database.

-- no foreign key on organization_id or object_id: a deleted tenant or object takes its records with
-- it, and the rows must stay for the cleanup job to find and delete their bytes.
CREATE TABLE ${metadataSchema}.stored_files (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    -- the object and field the upload was checked against: a write may only name a file uploaded for it
    object_id       uuid NOT NULL,
    field_name      text NOT NULL,
    -- <organization id>/<id>, never the client's file name
    object_key      text NOT NULL,
    file_name       text NOT NULL,
    content_type    text NOT NULL,
    size_bytes      bigint NOT NULL,
    sha256          text NOT NULL,
    created_by      uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT stored_files_object_key_unique UNIQUE (object_key),
    CONSTRAINT stored_files_size_valid CHECK (size_bytes >= 0),
    CONSTRAINT stored_files_sha256_valid CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- the cleanup walks one organization at a time, oldest first
CREATE INDEX stored_files_org_created_idx ON ${metadataSchema}.stored_files (organization_id, created_at);

-- field settings (R3): the size cap and the content-type allow-list, null meaning the configured default
ALTER TABLE ${metadataSchema}.custom_fields
    ADD COLUMN file_max_bytes     bigint,
    ADD COLUMN file_content_types text;

ALTER TABLE ${metadataSchema}.custom_fields
    ADD CONSTRAINT custom_fields_file_max_bytes_valid CHECK (file_max_bytes IS NULL OR file_max_bytes > 0);

-- what a FILE or IMAGE column reads as: the descriptor as json text, null when no such file in that
-- organization. the record select calls it with the row's own organization_id.
CREATE FUNCTION ${metadataSchema}.stored_file_descriptor(file_id uuid, org uuid) RETURNS text
    LANGUAGE sql STABLE AS $$
    SELECT json_build_object(
        'id', f.id, 'name', f.file_name, 'contentType', f.content_type, 'size', f.size_bytes, 'sha256', f.sha256
    )::text
    FROM ${metadataSchema}.stored_files f
    WHERE f.id = file_id AND f.organization_id = org
$$;

-- core's check lists the installed field types (R4). FILE and IMAGE are appended to it by
-- afterMigrate__file_types.sql, on every start, after this module's migrations.
