-- wasichai-documents: the original's V12 + V13 + V14 rebaselined (ADR-0026). final shape only, clean start.
--
-- a document type is a template an admin writes once and a record issues many times. it keys to an
-- object the way a form does -- several per object, named -- plus a prefix, the SGTM in SGTM-2026-001.
-- the prefix is unique per organization: the correlative counts per type, so two types sharing a
-- prefix would each issue their own SGTM-2026-001.

CREATE TABLE ${metadataSchema}.document_types (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    name text NOT NULL,
    label text NOT NULL,
    prefix text NOT NULL,
    template jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_types_name_unique_per_object UNIQUE (object_id, name),
    CONSTRAINT document_types_prefix_unique_per_org UNIQUE (organization_id, prefix)
);

CREATE INDEX document_types_object_idx ON ${metadataSchema}.document_types (organization_id, object_id);

-- the counter is a table, not a sequence: a sequence is not transactional, so a failed issue would
-- burn a number. this one moves with the transaction -- roll back and the number comes back.
CREATE TABLE ${metadataSchema}.document_counters (
    document_type_id uuid NOT NULL REFERENCES ${metadataSchema}.document_types (id) ON DELETE CASCADE,
    year integer NOT NULL,
    next integer NOT NULL DEFAULT 1,
    PRIMARY KEY (document_type_id, year)
);

-- issuing freezes a document: the row keeps the values AND the template as they were.
CREATE TABLE ${metadataSchema}.documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    -- RESTRICT: an issued document is a fact. a type that issued anything cannot be deleted under it.
    document_type_id uuid NOT NULL REFERENCES ${metadataSchema}.document_types (id) ON DELETE RESTRICT,
    object_id uuid NOT NULL REFERENCES ${metadataSchema}.custom_objects (id) ON DELETE CASCADE,
    record_id uuid NOT NULL,
    number text NOT NULL,
    year integer NOT NULL,
    sequence integer NOT NULL,
    status text NOT NULL DEFAULT 'VALID',
    snapshot jsonb NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    issued_by uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    CONSTRAINT documents_number_unique_per_org UNIQUE (organization_id, number),
    CONSTRAINT documents_status_valid CHECK (status IN ('VALID', 'ARCHIVED'))
);

-- "the last one issued prevails" is this index, not a rule in a service
CREATE UNIQUE INDEX documents_one_valid_per_record
    ON ${metadataSchema}.documents (document_type_id, record_id) WHERE status = 'VALID';

CREATE INDEX documents_record_idx ON ${metadataSchema}.documents (organization_id, object_id, record_id, issued_at DESC);

-- issuing is history too. core's audit_log already has document_id (no FK) and a CHECK without
-- ISSUE (P1 R10); this module owns both halves. same constraint name, so the final schema is the
-- original one. ON DELETE SET NULL: an audit entry must not vanish because its document did.
ALTER TABLE ${metadataSchema}.audit_log DROP CONSTRAINT audit_log_operation_valid;
ALTER TABLE ${metadataSchema}.audit_log ADD CONSTRAINT audit_log_operation_valid
    CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'ISSUE'));

ALTER TABLE ${metadataSchema}.audit_log
    ADD CONSTRAINT audit_log_document_id_fkey FOREIGN KEY (document_id) REFERENCES ${metadataSchema}.documents (id) ON DELETE SET NULL;
