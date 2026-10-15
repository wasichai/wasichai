-- the asPlatform block that staged a file with no person behind it (ADR-068): only a write of that same block
-- may attach it. null for a person's upload, which has created_by instead; never both.
ALTER TABLE ${metadataSchema}.stored_files
    ADD COLUMN platform_run uuid,
    ADD CONSTRAINT stored_files_one_uploader CHECK (created_by IS NULL OR platform_run IS NULL);
