-- a DATETIME field's own zone (issue 91, ADR-063): an IANA name, null for none. the column keeps
-- instants, so setting or changing it moves no data. checked by the service, not here: postgres
-- has no immutable way to ask whether a zone name exists.
ALTER TABLE ${metadataSchema}.custom_fields ADD COLUMN time_zone text;
