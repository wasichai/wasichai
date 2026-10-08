-- audit by user and period (ADR-052). GET /api/audit?userId=&from=&to= walks one user's rows of the tenant, newest
-- first: equality on the first two columns, the range and the order on the third. AuditPagingApiTest EXPLAINs it.
-- the (organization_id, occurred_at DESC) index of V1 already serves from/to alone.
CREATE INDEX audit_log_user_time_idx ON ${metadataSchema}.audit_log (organization_id, user_id, occurred_at DESC);
