-- object write rules (ADR-040). append_only: records take INSERT, never UPDATE or DELETE, for anyone.
-- api_only: the generic record api does not write the object; in-process callers do. both off by default.
ALTER TABLE ${metadataSchema}.custom_objects
    ADD COLUMN append_only boolean NOT NULL DEFAULT false,
    ADD COLUMN api_only    boolean NOT NULL DEFAULT false;
