-- when a download first found a file's bytes changed in the store (ADR-074). null while none did. the audit log
-- holds every failed check; this is the file's own mark, for a query or a repair job to find.
ALTER TABLE ${metadataSchema}.stored_files
    ADD COLUMN damaged_at timestamptz;
