--
-- PostgreSQL database dump
--

\restrict wasichaidemo


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: app_data; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "app_data";


--
-- Name: wasichai; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "wasichai";


--
-- Name: pgcrypto; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA "public";


--
-- Name: EXTENSION "pgcrypto"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "pgcrypto" IS 'cryptographic functions';


--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS "postgis" WITH SCHEMA "public";


--
-- Name: EXTENSION "postgis"; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION "postgis" IS 'PostGIS geometry and geography spatial types and functions';


--
-- Name: audit_log_guard(); Type: FUNCTION; Schema: wasichai; Owner: -
--

CREATE FUNCTION "wasichai"."audit_log_guard"() RETURNS "trigger"
    LANGUAGE "plpgsql"
    SET "search_path" TO 'pg_catalog', 'pg_temp'
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF pg_trigger_depth() > 1
            AND OLD.document_id IS NOT NULL AND NEW.document_id IS NULL
            AND (to_jsonb(OLD) - 'document_id') = (to_jsonb(NEW) - 'document_id') THEN
            RETURN NEW;
        END IF;
    ELSIF current_setting('wasichai.audit.purge', true) = 'on'
        AND session_user = wasichai.audit_log_purge_role() THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege',
              HINT = 'audit entries are never changed nor removed (ADR-054); a purge needs the role wasichai.audit.purge-role names';
END
$$;


--
-- Name: audit_log_purge_role(); Type: FUNCTION; Schema: wasichai; Owner: -
--

CREATE FUNCTION "wasichai"."audit_log_purge_role"() RETURNS "text"
    LANGUAGE "sql" STABLE
    AS $$ SELECT nullif('', '') $$;


--
-- Name: stored_file_descriptor("uuid", "uuid"); Type: FUNCTION; Schema: wasichai; Owner: -
--

CREATE FUNCTION "wasichai"."stored_file_descriptor"("file_id" "uuid", "org" "uuid") RETURNS "text"
    LANGUAGE "sql" STABLE
    AS $$
    SELECT json_build_object(
        'id', f.id, 'name', f.file_name, 'contentType', f.content_type, 'size', f.size_bytes, 'sha256', f.sha256
    )::text
    FROM wasichai.stored_files f
    WHERE f.id = file_id AND f.organization_id = org
$$;


SET default_tablespace = '';

SET default_table_access_method = "heap";

--
-- Name: contribuyente__00000000; Type: TABLE; Schema: app_data; Owner: -
--

CREATE TABLE "app_data"."contribuyente__00000000" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "created_by" "uuid",
    "updated_by" "uuid",
    "codigo" "text" NOT NULL,
    "nombre" "text" NOT NULL,
    "tipo" "text" NOT NULL,
    "documento" "text",
    "email" "text",
    "telefono" "text",
    "direccion" "text",
    "activo" boolean,
    CONSTRAINT "contribuyente__00000000_tipo_check" CHECK (("tipo" = ANY (ARRAY['PERSONA_NATURAL'::"text", 'PERSONA_JURIDICA'::"text"])))
);


--
-- Name: licencia__00000000; Type: TABLE; Schema: app_data; Owner: -
--

CREATE TABLE "app_data"."licencia__00000000" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "created_by" "uuid",
    "updated_by" "uuid",
    "codigo" "text" NOT NULL,
    "razon_social" "text" NOT NULL,
    "giro" "text" NOT NULL,
    "area_local" numeric,
    "fecha_solicitud" "date",
    "fecha_inspeccion" timestamp with time zone,
    "observaciones" "text",
    "resultado" "text",
    "solicitante" "uuid",
    "predio" "uuid",
    "workflow_state" "text",
    CONSTRAINT "licencia__00000000_giro_check" CHECK (("giro" = ANY (ARRAY['BODEGA'::"text", 'RESTAURANTE'::"text", 'FARMACIA'::"text", 'FERRETERIA'::"text", 'OFICINA'::"text", 'TALLER'::"text"])))
);


--
-- Name: predio__00000000; Type: TABLE; Schema: app_data; Owner: -
--

CREATE TABLE "app_data"."predio__00000000" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "created_by" "uuid",
    "updated_by" "uuid",
    "codigo" "text" NOT NULL,
    "direccion" "text",
    "area" numeric,
    "uso" "text",
    "valor_autovaluo" numeric,
    "lote" "public"."geometry"(Polygon,4326),
    "titular" "uuid",
    CONSTRAINT "predio__00000000_uso_check" CHECK (("uso" = ANY (ARRAY['RESIDENCIAL'::"text", 'COMERCIAL'::"text", 'INDUSTRIAL'::"text", 'AGRICOLA'::"text"])))
);


--
-- Name: audit_log; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."audit_log" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "user_id" "uuid",
    "object_name" "text" NOT NULL,
    "record_id" "uuid",
    "operation" "text" NOT NULL,
    "before_state" "jsonb",
    "after_state" "jsonb",
    "occurred_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "document_id" "uuid",
    "reason" "text",
    "correlation_id" "text",
    "source" "text",
    CONSTRAINT "audit_log_operation_valid" CHECK (("operation" = ANY (ARRAY['CREATE'::"text", 'UPDATE'::"text", 'DELETE'::"text", 'ISSUE'::"text"])))
);


--
-- Name: automation_runs; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."automation_runs" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "automation_id" "uuid" NOT NULL,
    "object_name" "text" NOT NULL,
    "record_id" "uuid",
    "trigger_type" "text" NOT NULL,
    "status" "text" NOT NULL,
    "depth" integer DEFAULT 0 NOT NULL,
    "payload" "jsonb" NOT NULL,
    "steps" "jsonb",
    "error" "text",
    "attempts" integer DEFAULT 0 NOT NULL,
    "user_id" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "started_at" timestamp with time zone,
    "finished_at" timestamp with time zone,
    "correlation_id" "text"
);


--
-- Name: automations; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."automations" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: custom_fields; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."custom_fields" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "type" "text" NOT NULL,
    "column_name" "text" NOT NULL,
    "required" boolean DEFAULT false NOT NULL,
    "is_unique" boolean DEFAULT false NOT NULL,
    "default_value" "text",
    "description" "text",
    "position" integer DEFAULT 0 NOT NULL,
    "validation" "jsonb",
    "enum_options" "jsonb",
    "relation_target_object_id" "uuid",
    "visible" boolean DEFAULT true NOT NULL,
    "editable" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "indexed" boolean DEFAULT false NOT NULL,
    "time_zone" "text",
    "geometry_type" "text",
    "srid" integer,
    "dimension" integer,
    "file_max_bytes" bigint,
    "file_content_types" "text",
    CONSTRAINT "custom_fields_dimension_valid" CHECK ((("dimension" IS NULL) OR ("dimension" = ANY (ARRAY[2, 3])))),
    CONSTRAINT "custom_fields_enum_has_options" CHECK ((("type" <> 'ENUM'::"text") OR ("enum_options" IS NOT NULL))),
    CONSTRAINT "custom_fields_file_max_bytes_valid" CHECK ((("file_max_bytes" IS NULL) OR ("file_max_bytes" > 0))),
    CONSTRAINT "custom_fields_geometry_has_type" CHECK ((("type" <> 'GEOMETRY'::"text") OR (("geometry_type" IS NOT NULL) AND ("srid" IS NOT NULL) AND ("dimension" IS NOT NULL)))),
    CONSTRAINT "custom_fields_geometry_type_valid" CHECK ((("geometry_type" IS NULL) OR ("geometry_type" = ANY (ARRAY['POINT'::"text", 'LINESTRING'::"text", 'POLYGON'::"text", 'MULTIPOINT'::"text", 'MULTILINESTRING'::"text", 'MULTIPOLYGON'::"text"])))),
    CONSTRAINT "custom_fields_relation_has_target" CHECK ((("type" <> 'RELATION'::"text") OR ("relation_target_object_id" IS NOT NULL))),
    CONSTRAINT "custom_fields_type_valid" CHECK (("type" = ANY (ARRAY['TEXT'::"text", 'LONG_TEXT'::"text", 'INTEGER'::"text", 'DECIMAL'::"text", 'BOOLEAN'::"text", 'DATE'::"text", 'DATETIME'::"text", 'ENUM'::"text", 'EMAIL'::"text", 'URL'::"text", 'UUID'::"text", 'RELATION'::"text", 'GEOMETRY'::"text", 'FILE'::"text", 'IMAGE'::"text"])))
);


--
-- Name: custom_objects; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."custom_objects" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "plural_label" "text" NOT NULL,
    "description" "text",
    "enabled" boolean DEFAULT true NOT NULL,
    "physical_table" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "indexes" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "unique_constraints" "jsonb" DEFAULT '[]'::"jsonb" NOT NULL,
    "append_only" boolean DEFAULT false NOT NULL,
    "api_only" boolean DEFAULT false NOT NULL,
    "requires_reason" boolean DEFAULT false NOT NULL
);


--
-- Name: document_counters; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."document_counters" (
    "document_type_id" "uuid" NOT NULL,
    "year" integer NOT NULL,
    "next" integer DEFAULT 1 NOT NULL
);


--
-- Name: document_types; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."document_types" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "prefix" "text" NOT NULL,
    "template" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: documents; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."documents" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "document_type_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "record_id" "uuid" NOT NULL,
    "number" "text" NOT NULL,
    "year" integer NOT NULL,
    "sequence" integer NOT NULL,
    "status" "text" DEFAULT 'VALID'::"text" NOT NULL,
    "snapshot" "jsonb" NOT NULL,
    "issued_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "issued_by" "uuid",
    CONSTRAINT "documents_status_valid" CHECK (("status" = ANY (ARRAY['VALID'::"text", 'ARCHIVED'::"text"])))
);


--
-- Name: field_permissions; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."field_permissions" (
    "role_id" "uuid" NOT NULL,
    "field_id" "uuid" NOT NULL,
    "can_read" boolean DEFAULT true NOT NULL,
    "can_write" boolean DEFAULT true NOT NULL
);


--
-- Name: flyway_history_automation; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_automation" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_core; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_core" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_core_seed; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_core_seed" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_documents; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_documents" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_files; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_files" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_forms; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_forms" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_gis; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_gis" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_notifications; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_notifications" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_pages; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_pages" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_views; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_views" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: flyway_history_workflow; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."flyway_history_workflow" (
    "installed_rank" integer NOT NULL,
    "version" character varying(50),
    "description" character varying(200) NOT NULL,
    "type" character varying(20) NOT NULL,
    "script" character varying(1000) NOT NULL,
    "checksum" integer,
    "installed_by" character varying(100) NOT NULL,
    "installed_on" timestamp without time zone DEFAULT "now"() NOT NULL,
    "execution_time" integer NOT NULL,
    "success" boolean NOT NULL
);


--
-- Name: forms; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."forms" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "organization_id" "uuid" NOT NULL
);


--
-- Name: idempotency_keys; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."idempotency_keys" (
    "organization_id" "uuid" NOT NULL,
    "user_id" "uuid",
    "key" "text" NOT NULL,
    "request_hash" "text" NOT NULL,
    "response_status" integer NOT NULL,
    "response_body" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "idempotency_keys_key_valid" CHECK ((("length"("key") >= 1) AND ("length"("key") <= 128)))
);


--
-- Name: notification_deliveries; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_deliveries" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "notification_id" "uuid" NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "channel" "text" NOT NULL,
    "status" "text" DEFAULT 'PENDING'::"text" NOT NULL,
    "attempts" integer DEFAULT 0 NOT NULL,
    "last_error" "text",
    "next_attempt_at" timestamp with time zone NOT NULL,
    "sent_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "notification_deliveries_status_valid" CHECK (("status" = ANY (ARRAY['PENDING'::"text", 'SENT'::"text", 'FAILED'::"text", 'SKIPPED'::"text"])))
);


--
-- Name: notification_preferences; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_preferences" (
    "user_id" "uuid" NOT NULL,
    "channel" "text" NOT NULL,
    "kinds" "text"[] NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "notification_preferences_kinds_valid" CHECK (("kinds" <@ ARRAY['INFO'::"text", 'WARNING'::"text", 'ACTION'::"text"]))
);


--
-- Name: notification_receipts; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_receipts" (
    "notification_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "read_at" timestamp with time zone,
    "dismissed_at" timestamp with time zone,
    "snoozed_until" timestamp with time zone
);


--
-- Name: notification_rules; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_rules" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "notification_rules_name_valid" CHECK (("name" ~ '^[a-z][a-z0-9_]{1,48}$'::"text"))
);


--
-- Name: notification_source_runs; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_source_runs" (
    "source" "text" NOT NULL,
    "last_run_at" timestamp with time zone NOT NULL
);


--
-- Name: notification_targets; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notification_targets" (
    "notification_id" "uuid" NOT NULL,
    "type" "text" NOT NULL,
    "user_id" "uuid",
    "role_name" "text",
    "unit_id" "uuid",
    CONSTRAINT "notification_targets_shape" CHECK (((("type" = 'USER'::"text") = ("user_id" IS NOT NULL)) AND (("type" = 'ROLE'::"text") = ("role_name" IS NOT NULL)) AND (("type" = 'UNIT'::"text") = ("unit_id" IS NOT NULL)))),
    CONSTRAINT "notification_targets_type_valid" CHECK (("type" = ANY (ARRAY['ALL'::"text", 'USER'::"text", 'ROLE'::"text", 'UNIT'::"text"])))
);


--
-- Name: notifications; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."notifications" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "kind" "text" NOT NULL,
    "title" "text" NOT NULL,
    "body" "text",
    "link" "jsonb",
    "link_object_id" "uuid",
    "publish_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "expires_at" timestamp with time zone,
    "due_at" timestamp with time zone,
    "source" "text" NOT NULL,
    "source_key" "text",
    "fingerprint" "text" NOT NULL,
    "resolved_at" timestamp with time zone,
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "notifications_kind_valid" CHECK (("kind" = ANY (ARRAY['INFO'::"text", 'WARNING'::"text", 'ACTION'::"text"]))),
    CONSTRAINT "notifications_window_valid" CHECK ((("expires_at" IS NULL) OR ("expires_at" > "publish_at")))
);


--
-- Name: object_actions; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."object_actions" (
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "object_actions_name_valid" CHECK (("name" ~ '^[A-Z][A-Z0-9_]{1,48}$'::"text")),
    CONSTRAINT "object_actions_not_builtin" CHECK (("name" <> ALL (ARRAY['READ'::"text", 'CREATE'::"text", 'UPDATE'::"text", 'DELETE'::"text", 'MANAGE_METADATA'::"text", 'MANAGE_ORGANIZATION'::"text", 'MANAGE_TENANTS'::"text"])))
);


--
-- Name: org_units; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."org_units" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "parent_id" "uuid",
    "code" "text" NOT NULL,
    "label" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "org_units_code_valid" CHECK (("code" ~ '^[A-Z][A-Z0-9_]{1,48}$'::"text")),
    CONSTRAINT "org_units_label_valid" CHECK ((("length"("label") >= 1) AND ("length"("label") <= 120))),
    CONSTRAINT "org_units_not_own_parent" CHECK (("parent_id" <> "id"))
);


--
-- Name: organizations; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."organizations" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "name" "text" NOT NULL,
    "slug" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: pages; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."pages" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "object_id" "uuid",
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "kind" "text" DEFAULT 'RECORD_DETAIL'::"text" NOT NULL,
    "template" "text" DEFAULT 'one-region'::"text" NOT NULL,
    CONSTRAINT "pages_kind_valid" CHECK (("kind" = 'RECORD_DETAIL'::"text"))
);


--
-- Name: permissions; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."permissions" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "role_id" "uuid" NOT NULL,
    "object_id" "uuid",
    "action" "text" NOT NULL,
    "allowed" boolean DEFAULT true NOT NULL,
    "declared_object_id" "uuid" GENERATED ALWAYS AS (
CASE
    WHEN ("action" = ANY (ARRAY['READ'::"text", 'CREATE'::"text", 'UPDATE'::"text", 'DELETE'::"text", 'MANAGE_METADATA'::"text", 'MANAGE_ORGANIZATION'::"text"])) THEN NULL::"uuid"
    ELSE "object_id"
END) STORED,
    CONSTRAINT "permissions_action_valid" CHECK ((("action" = ANY (ARRAY['READ'::"text", 'CREATE'::"text", 'UPDATE'::"text", 'DELETE'::"text", 'MANAGE_METADATA'::"text", 'MANAGE_ORGANIZATION'::"text", 'MANAGE_TENANTS'::"text"])) OR ("object_id" IS NOT NULL))),
    CONSTRAINT "permissions_tenants_no_object" CHECK ((("action" <> 'MANAGE_TENANTS'::"text") OR ("object_id" IS NULL)))
);


--
-- Name: relationships; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."relationships" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "type" "text" NOT NULL,
    "source_object_id" "uuid" NOT NULL,
    "target_object_id" "uuid" NOT NULL,
    "relation_field_id" "uuid",
    "join_table" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "inverse_label" "text",
    CONSTRAINT "relationships_type_valid" CHECK (("type" = ANY (ARRAY['ONE_TO_ONE'::"text", 'ONE_TO_MANY'::"text", 'MANY_TO_ONE'::"text", 'MANY_TO_MANY'::"text"])))
);


--
-- Name: roles; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."roles" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "own_records_only" boolean DEFAULT false NOT NULL
);


--
-- Name: service_accounts; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."service_accounts" (
    "id" "uuid" NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "secret_hash" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "secret_rotated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "service_accounts_name_valid" CHECK (("name" ~ '^[a-z][a-z0-9_-]{1,48}$'::"text"))
);


--
-- Name: stored_files; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."stored_files" (
    "id" "uuid" NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "field_name" "text" NOT NULL,
    "object_key" "text" NOT NULL,
    "file_name" "text" NOT NULL,
    "content_type" "text" NOT NULL,
    "size_bytes" bigint NOT NULL,
    "sha256" "text" NOT NULL,
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "platform_run" "uuid",
    CONSTRAINT "stored_files_one_uploader" CHECK ((("created_by" IS NULL) OR ("platform_run" IS NULL))),
    CONSTRAINT "stored_files_sha256_valid" CHECK (("sha256" ~ '^[0-9a-f]{64}$'::"text")),
    CONSTRAINT "stored_files_size_valid" CHECK (("size_bytes" >= 0))
);


--
-- Name: user_org_units; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."user_org_units" (
    "user_id" "uuid" NOT NULL,
    "unit_id" "uuid" NOT NULL
);


--
-- Name: user_preferences; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."user_preferences" (
    "user_id" "uuid" NOT NULL,
    "theme" "text" DEFAULT 'system'::"text" NOT NULL,
    "locale" "text",
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Name: user_roles; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."user_roles" (
    "user_id" "uuid" NOT NULL,
    "role_id" "uuid" NOT NULL
);


--
-- Name: users; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."users" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "email" "text" NOT NULL,
    "password_hash" "text" NOT NULL,
    "display_name" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "tokens_valid_after" timestamp with time zone
);


--
-- Name: views; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."views" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "is_default" boolean DEFAULT false NOT NULL
);


--
-- Name: workflows; Type: TABLE; Schema: wasichai; Owner: -
--

CREATE TABLE "wasichai"."workflows" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "organization_id" "uuid" NOT NULL,
    "object_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "label" "text" NOT NULL,
    "enabled" boolean DEFAULT true NOT NULL,
    "definition" "jsonb" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL
);


--
-- Data for Name: contribuyente__00000000; Type: TABLE DATA; Schema: app_data; Owner: -
--

INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('00122049-9609-495d-84c4-f526d8cc0cee', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:08.805151+00', '2026-10-20 09:42:08.805151+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0001', 'Ana Cáceres Castillo', 'PERSONA_NATURAL', '53470582', 'ana.caceres.castillo@correo.example', '919605159', 'Av. El Sol 865, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('63b995af-c0ef-4290-8981-eab21020a4e4', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:08.883871+00', '2026-10-20 09:42:08.883871+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0002', 'Pedro Torres Paredes', 'PERSONA_NATURAL', '76581643', 'pedro.torres.paredes@correo.example', '902577483', 'Av. Los Próceres 193, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('26e36d67-2d3a-48c0-8569-aa97fa272bad', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:08.949418+00', '2026-10-20 09:42:08.949418+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0003', 'Comercial Andina S.A.C.', 'PERSONA_JURIDICA', '20198737142', 'comercial.andina.s.a.c@correo.example', '958476538', 'Jr. Las Begonias 561, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('7c9fb903-a304-4e72-8f48-9bd9340a4d82', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:08.996601+00', '2026-10-20 09:42:08.996601+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0004', 'José Flores Paredes', 'PERSONA_NATURAL', '39768829', 'jose.flores.paredes@correo.example', '976430319', 'Jr. Las Begonias 330, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('5735071c-f421-4bf8-801a-ab912ab8186f', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.051173+00', '2026-10-20 09:42:09.051173+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0005', 'Sofía Mamani Vargas', 'PERSONA_NATURAL', '49276830', 'sofia.mamani.vargas@correo.example', '988196682', 'Calle Las Gardenias 359, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.107744+00', '2026-10-20 09:42:09.107744+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0006', 'Inversiones Rímac S.A.C.', 'PERSONA_JURIDICA', '20588030075', 'inversiones.rimac.s.a.c@correo.example', '946024059', 'Jr. Las Begonias 434, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('79067a68-4339-4d4b-a7b2-f2c06f9ebfa9', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.172458+00', '2026-10-20 09:42:09.172458+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0007', 'María Quispe Chávez', 'PERSONA_NATURAL', '63440664', 'maria.quispe.chavez@correo.example', '975292336', 'Av. Primavera 635, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('846dbc52-4c85-4b69-a752-a035c14add2a', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.229175+00', '2026-10-20 09:42:09.229175+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0008', 'Lucía Salazar Huamán', 'PERSONA_NATURAL', '91261268', 'lucia.salazar.huaman@correo.example', '986069130', 'Calle Las Gardenias 696, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('5c59a1f5-97ae-45cf-9ef3-f142f8a41f75', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.286744+00', '2026-10-20 09:42:09.286744+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0009', 'Servicios Pachacútec S.A.C.', 'PERSONA_JURIDICA', '20712579845', 'servicios.pachacutec.s.a@correo.example', '951383995', 'Av. Primavera 528, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('12f034e4-6351-4fa7-aca0-bfef1afb6f29', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.336812+00', '2026-10-20 09:42:09.336812+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0010', 'Jorge Rojas Cáceres', 'PERSONA_NATURAL', '13302271', 'jorge.rojas.caceres@correo.example', '950711371', 'Av. Primavera 458, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('2f24c06a-1df3-4791-8330-56897fa5a0d0', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.389506+00', '2026-10-20 09:42:09.389506+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0011', 'Sofía Torres Vargas', 'PERSONA_NATURAL', '92784996', 'sofia.torres.vargas@correo.example', '916509497', 'Av. Los Próceres 882, Distrito Demo', false);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('16b55360-6e5a-4721-acb4-7ea60bec232d', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.428346+00', '2026-10-20 09:42:09.428346+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0012', 'Distribuidora Los Olivos S.A.C.', 'PERSONA_JURIDICA', '20214096236', 'distribuidora.los.olivos@correo.example', '963973882', 'Av. Primavera 436, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('f7f597ae-3131-4f67-9a98-693c6dbaf031', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.474223+00', '2026-10-20 09:42:09.474223+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0013', 'Miguel Salazar Salazar', 'PERSONA_NATURAL', '90530009', 'miguel.salazar.salazar@correo.example', '994015895', 'Jr. Huallaga 706, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.52913+00', '2026-10-20 09:42:09.52913+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0014', 'Elena Vargas Salazar', 'PERSONA_NATURAL', '50338974', 'elena.vargas.salazar@correo.example', '990082418', 'Av. El Sol 210, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('c4551f79-e2fa-4de7-a374-59e6ae3ea37c', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.57476+00', '2026-10-20 09:42:09.57476+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0015', 'Grupo Miraflores S.A.C.', 'PERSONA_JURIDICA', '20113177719', 'grupo.miraflores.s.a.c@correo.example', '977424244', 'Calle Las Gardenias 127, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('170f415c-27ce-4b59-9acb-027585d7f293', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.616455+00', '2026-10-20 09:42:09.616455+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0016', 'Teresa Flores Vargas', 'PERSONA_NATURAL', '54042236', 'teresa.flores.vargas@correo.example', '931307053', 'Av. El Sol 110, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('f4b4c79c-3b1b-4686-b81d-45f19ec00640', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.654388+00', '2026-10-20 09:42:09.654388+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0017', 'Carmen Vargas Huamán', 'PERSONA_NATURAL', '14217143', 'carmen.vargas.huaman@correo.example', '960644310', 'Jr. Ica 833, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('7b2b6529-6c81-444f-950d-1635e123803d', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.692299+00', '2026-10-20 09:42:09.692299+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0018', 'Textiles del Sur S.A.C.', 'PERSONA_JURIDICA', '20612908744', 'textiles.del.sur.s.a.c@correo.example', '910430526', 'Calle Las Gardenias 851, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('4bf492c9-9874-443c-9f24-74d0643acb92', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.741501+00', '2026-10-20 09:42:09.741501+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0019', 'Ana Cáceres Torres', 'PERSONA_NATURAL', '34362300', 'ana.caceres.torres@correo.example', '970351778', 'Av. Primavera 958, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('68d95c39-57f5-4d1f-8595-153df4f76aa5', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.786815+00', '2026-10-20 09:42:09.786815+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0020', 'Jorge Chávez Ramos', 'PERSONA_NATURAL', '65345762', 'jorge.chavez.ramos@correo.example', '923794305', 'Calle Las Gardenias 392, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('4a5537fa-da47-45c1-ae1c-c3a96d52c2c1', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.824656+00', '2026-10-20 09:42:09.824656+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0021', 'Agroindustrias Chancay S.A.C.', 'PERSONA_JURIDICA', '20149039149', 'agroindustrias.chancay.s@correo.example', '948570039', 'Av. Primavera 337, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('ed8caf7c-8bfe-474f-872b-46b7b39f2454', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.864041+00', '2026-10-20 09:42:09.864041+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0022', 'Jorge Paredes Mamani', 'PERSONA_NATURAL', '14078129', 'jorge.paredes.mamani@correo.example', '973623900', 'Jr. Las Begonias 394, Distrito Demo', false);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('d7cbcddf-68ca-42a4-b3b3-00f5ce99a573', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.902254+00', '2026-10-20 09:42:09.902254+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0023', 'Lucía Castillo Cáceres', 'PERSONA_NATURAL', '65782490', 'lucia.castillo.caceres@correo.example', '975600492', 'Av. Los Próceres 830, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('681cf3b9-50de-4f12-aa8e-4093b43e3db5', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.939604+00', '2026-10-20 09:42:09.939604+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0024', 'Ferretería El Progreso S.A.C.', 'PERSONA_JURIDICA', '20508010339', 'ferreteria.el.progreso.s@correo.example', '924149560', 'Av. Primavera 680, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('8e6d1243-a7c5-4aac-9b83-88b463337076', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:09.977139+00', '2026-10-20 09:42:09.977139+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0025', 'Luis Medina Flores', 'PERSONA_NATURAL', '76568545', 'luis.medina.flores@correo.example', '925641834', 'Av. Primavera 857, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('50ee49d8-325e-4564-ae56-a5de0709a456', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.010704+00', '2026-10-20 09:42:10.010704+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0026', 'Raúl Rojas Paredes', 'PERSONA_NATURAL', '48322125', 'raul.rojas.paredes@correo.example', '923651563', 'Av. El Sol 376, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('aac7c6c4-5c4f-4228-8a96-4786e71ccf9e', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.070803+00', '2026-10-20 09:42:10.070803+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0027', 'Botica Santa Rosa S.A.C.', 'PERSONA_JURIDICA', '20764438533', 'botica.santa.rosa.s.a.c@correo.example', '977216433', 'Av. Los Próceres 436, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('fce2430d-6b2d-4c23-a75d-45ddf38ff015', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.113848+00', '2026-10-20 09:42:10.113848+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0028', 'María Quispe Salazar', 'PERSONA_NATURAL', '99179682', 'maria.quispe.salazar@correo.example', '949009322', 'Av. Los Próceres 784, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('a81f7da9-9ec6-4621-9615-bb97ede93564', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.157906+00', '2026-10-20 09:42:10.157906+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0029', 'Víctor Chávez Cáceres', 'PERSONA_NATURAL', '60959642', 'victor.chavez.caceres@correo.example', '954207550', 'Jr. Huallaga 765, Distrito Demo', true);
INSERT INTO "app_data"."contribuyente__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "nombre", "tipo", "documento", "email", "telefono", "direccion", "activo") VALUES ('2d8fbbd8-3413-465c-a318-596a8b8c2989', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.200821+00', '2026-10-20 09:42:10.200821+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'C-0030', 'Restaurante La Huerta S.A.C.', 'PERSONA_JURIDICA', '20175875079', 'restaurante.la.huerta.s.@correo.example', '943254604', 'Calle Las Gardenias 395, Distrito Demo', true);


--
-- Data for Name: licencia__00000000; Type: TABLE DATA; Schema: app_data; Owner: -
--

INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('3696eeb1-3a66-4717-a255-0bdd6feac753', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.483146+00', '2026-10-20 09:42:11.529797+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0001', 'Bodega Mamani 1', 'RESTAURANTE', 228.3, '2026-07-04', NULL, NULL, NULL, '16b55360-6e5a-4721-acb4-7ea60bec232d', '5f17835c-6d2f-4b5b-b5e5-341ccb3a574f', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('5f31a5d3-a189-4023-933f-7f1df56de85c', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.828481+00', '2026-10-20 09:42:11.852311+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0003', 'Estudio Chávez 3', 'FERRETERIA', 53, '2026-07-10', NULL, NULL, NULL, '7c9fb903-a304-4e72-8f48-9bd9340a4d82', 'f579c281-f200-40e1-96cd-8deaa7f0e553', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('f3cd7c83-2a8f-4195-a15b-2f7b72959ef1', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.067038+00', '2026-10-20 09:42:12.067038+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0005', 'Restaurante Huamán 5', 'TALLER', 53.5, '2026-07-16', NULL, NULL, NULL, '50ee49d8-325e-4564-ae56-a5de0709a456', 'fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('ecbe4879-eca3-4cf4-8f2f-25224eab99bd', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.105392+00', '2026-10-20 09:42:12.162579+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0006', 'Restaurante Vargas 6', 'BODEGA', 185.5, '2026-07-19', NULL, NULL, NULL, '79067a68-4339-4d4b-a7b2-f2c06f9ebfa9', '766b8f85-b5e6-4870-abe2-e6677e1e9bc5', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('e93a1aa3-9d89-4066-a7b8-201cd9b20a51', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.203026+00', '2026-10-20 09:42:12.250687+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0007', 'Taller Rojas 7', 'RESTAURANTE', 107.2, '2026-07-22', NULL, NULL, NULL, '7b2b6529-6c81-444f-950d-1635e123803d', 'fc88650a-e9dc-4122-9530-9466e16c5394', 'rechazada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('f1fab9f1-f034-4ded-be41-42f93b80f817', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.295974+00', '2026-10-20 09:42:12.325669+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0008', 'Estudio Castillo 8', 'FARMACIA', 260.3, '2026-07-25', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, 'a81f7da9-9ec6-4621-9615-bb97ede93564', 'a84b0be0-62c8-4ff6-b3a6-54857c5218b0', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('24e2a46b-913c-4e70-b075-17229a78f771', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.5354+00', '2026-10-20 09:42:12.562244+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0010', 'Taller Flores 10', 'OFICINA', 281.1, '2026-07-04', NULL, NULL, NULL, '4a5537fa-da47-45c1-ae1c-c3a96d52c2c1', '3a7aa199-1854-4416-97be-4a329cbc3c78', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('71566a41-ef9f-44e3-bf19-42df253384de', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.787131+00', '2026-10-20 09:42:12.787131+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0012', 'Botica Paredes 12', 'BODEGA', 105.2, '2026-07-10', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, 'f7f597ae-3131-4f67-9a98-693c6dbaf031', '227e7b9c-ed20-40f3-9d97-c38c75f06eb8', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('f65ac4de-c341-49f3-88ba-cf3a2265a374', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.842605+00', '2026-10-20 09:42:12.894+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0013', 'Botica Vargas 13', 'RESTAURANTE', 156.1, '2026-08-13', NULL, NULL, NULL, '681cf3b9-50de-4f12-aa8e-4093b43e3db5', 'e74d2236-a0bc-4942-8523-73dd76995381', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.896675+00', '2026-10-20 09:42:12.989909+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0004', 'Taller Castillo 4', 'OFICINA', 264.6, '2026-07-13', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', 'Aprobada el 2026-10-20', 'c4551f79-e2fa-4de7-a374-59e6ae3ea37c', '8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('9263f1a8-12a3-46aa-8f04-a1505239fa45', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.961367+00', '2026-10-20 09:42:13.039627+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0014', 'Taller Castillo 14', 'FARMACIA', 221.7, '2026-08-16', NULL, NULL, NULL, '5735071c-f421-4bf8-801a-ab912ab8186f', '4e105a70-c0fc-46c9-8d80-51532fa3ac8c', 'rechazada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('98778346-7f16-4252-be16-6781cfd7b6a2', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.088178+00', '2026-10-20 09:42:13.125314+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0015', 'Restaurante Torres 15', 'FERRETERIA', 208.2, '2026-08-19', NULL, NULL, NULL, '170f415c-27ce-4b59-9acb-027585d7f293', 'a26ddbbf-828d-4d0e-aa38-c46298053f2d', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('b24a4af3-3f66-42f3-8105-40671715bd89', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.382907+00', '2026-10-20 09:42:13.126216+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0009', 'Estudio Quispe 9', 'FERRETERIA', 151.7, '2026-07-01', NULL, NULL, 'Aprobada el 2026-10-20', '12f034e4-6351-4fa7-aca0-bfef1afb6f29', '8c717ce7-5524-4560-99bd-5b02c47f3fce', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('59155d81-0f56-46b2-a04a-b32ec7fe7997', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.62301+00', '2026-10-20 09:42:13.252969+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0002', 'Bodega Vargas 2', 'FARMACIA', 196.4, '2026-07-07', NULL, NULL, 'Aprobada el 2026-10-20', 'd7cbcddf-68ca-42a4-b3b3-00f5ce99a573', 'a9f7e251-496c-44f1-9f40-12f253554622', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('1cfb238e-0268-4a9b-91e4-cf84758797f1', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.363992+00', '2026-10-20 09:42:13.389739+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0017', 'Taller Ramos 17', 'TALLER', 245.3, '2026-08-25', NULL, NULL, NULL, '846dbc52-4c85-4b69-a752-a035c14add2a', 'ad910484-8b39-4f3f-8195-7882b6076d87', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('a9ea42e6-aaa9-4217-8091-ba5fc84fd32b', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.577104+00', '2026-10-20 09:42:13.577104+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0019', 'Restaurante Torres 19', 'RESTAURANTE', 208.7, '2026-08-04', NULL, NULL, NULL, '2d8fbbd8-3413-465c-a318-596a8b8c2989', 'd1f180c9-1e14-4559-93dd-75697358f83d', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('5af20f44-aa18-476c-bf07-1b844de0b892', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.612047+00', '2026-10-20 09:42:13.662814+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0020', 'Estudio Medina 20', 'FARMACIA', 158.5, '2026-08-07', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, '2f24c06a-1df3-4791-8330-56897fa5a0d0', '540f8841-1d8c-47e7-b126-ecbbcddc2b1e', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('fbf8eaee-e14d-4843-9d09-b470a9653f92', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:12.608437+00', '2026-10-20 09:42:13.785749+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0011', 'Estudio Mamani 11', 'TALLER', 100.2, '2026-07-07', NULL, NULL, 'Aprobada el 2026-10-20', '63b995af-c0ef-4290-8981-eab21020a4e4', 'd1548202-a8ae-4a63-9196-4f8894264a7b', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('52b50a86-29eb-4ac6-8947-776ddd57bb8b', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.72527+00', '2026-10-20 09:42:13.787314+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0021', 'Taller Chávez 21', 'FERRETERIA', 21.9, '2026-08-10', NULL, NULL, NULL, 'ed8caf7c-8bfe-474f-872b-46b7b39f2454', '3b211ef7-e67f-4a3d-9a0b-12979deb7cde', 'rechazada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('9b70de67-dde6-4eff-8940-f5665ebf8529', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.837463+00', '2026-10-20 09:42:13.867031+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0022', 'Restaurante Ramos 22', 'OFICINA', 105.8, '2026-08-13', NULL, NULL, NULL, '26e36d67-2d3a-48c0-8569-aa97fa272bad', '4a6042dc-228f-4671-b0e3-7f340b43e685', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('d16e695d-720a-4744-91af-aeb575bedce2', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.177151+00', '2026-10-20 09:42:14.006048+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0016', 'Taller Castillo 16', 'OFICINA', 170.6, '2026-08-22', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', 'Aprobada el 2026-10-20', 'aac7c6c4-5c4f-4228-8a96-4786e71ccf9e', '46222881-cddc-4be0-874c-331c2c07cdb0', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('9d556da0-f59b-4a79-a728-bd63536ddc45', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.433885+00', '2026-10-20 09:42:14.070135+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0018', 'Estudio Torres 18', 'BODEGA', 260.4, '2026-08-01', NULL, NULL, 'Aprobada el 2026-10-20', '4bf492c9-9874-443c-9f24-74d0643acb92', '2c0c3d6c-c603-4757-8a69-ceaac3546e64', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('1044e268-2c76-4923-8bab-cbeccad576c6', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.064426+00', '2026-10-20 09:42:14.088223+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0024', 'Restaurante Rojas 24', 'BODEGA', 76.6, '2026-08-19', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, '8e6d1243-a7c5-4aac-9b83-88b463337076', '73cb1aff-421a-4366-a9da-e990f6e815ee', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('0b4c15d7-e07d-4c97-b616-47920123d650', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.284145+00', '2026-10-20 09:42:14.284145+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0026', 'Restaurante Quispe 26', 'FARMACIA', 107.5, '2026-09-25', NULL, NULL, NULL, 'f4b4c79c-3b1b-4686-b81d-45f19ec00640', '5f17835c-6d2f-4b5b-b5e5-341ccb3a574f', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('54954b2b-23a1-421e-a341-21618051b2a9', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.321988+00', '2026-10-20 09:42:14.370371+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0027', 'Botica Chávez 27', 'FERRETERIA', 252.6, '2026-09-01', NULL, NULL, NULL, 'fce2430d-6b2d-4c23-a75d-45ddf38ff015', 'a9f7e251-496c-44f1-9f40-12f253554622', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('eba4a091-eca0-43b9-b20a-71e7379c9157', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.414296+00', '2026-10-20 09:42:14.478311+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0028', 'Estudio Paredes 28', 'OFICINA', 27.1, '2026-09-04', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, '5c59a1f5-97ae-45cf-9ef3-f142f8a41f75', 'f579c281-f200-40e1-96cd-8deaa7f0e553', 'rechazada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('a0da9137-f268-4f34-965e-2fa6fe299f64', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.532662+00', '2026-10-20 09:42:14.555564+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0029', 'Restaurante Cáceres 29', 'TALLER', 210.7, '2026-09-07', NULL, NULL, NULL, '68d95c39-57f5-4d1f-8595-153df4f76aa5', '8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('9c31cc19-7b21-4484-aa64-ed14a2304516', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.723553+00', '2026-10-20 09:42:14.723553+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0031', 'Ferretería Flores 31', 'RESTAURANTE', 228.7, '2026-09-13', NULL, NULL, NULL, '16b55360-6e5a-4721-acb4-7ea60bec232d', '766b8f85-b5e6-4870-abe2-e6677e1e9bc5', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('817ac2ac-ff28-4b1f-bc10-b4381ea89a16', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.130639+00', '2026-10-20 09:42:14.738726+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0025', 'Botica Medina 25', 'RESTAURANTE', 104.7, '2026-09-22', NULL, NULL, 'Aprobada el 2026-10-20', '16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3', 'a5f07856-8281-4392-9a55-720ac56fe5fe', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('6db3ac47-a69d-472f-8b31-50476b8fe484', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:13.907506+00', '2026-10-20 09:42:14.797224+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0023', 'Botica Salazar 23', 'TALLER', 284.7, '2026-08-16', NULL, NULL, 'Aprobada el 2026-10-20', '21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7', 'ba9b3af8-e337-4d4d-8de6-e1b8f926b06d', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('6f89b57e-dac8-4395-ac6b-f494603836e0', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.874967+00', '2026-10-20 09:42:14.874967+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0033', 'Ferretería Medina 33', 'FERRETERIA', 155.7, '2026-09-19', NULL, NULL, NULL, '7c9fb903-a304-4e72-8f48-9bd9340a4d82', 'a84b0be0-62c8-4ff6-b3a6-54857c5218b0', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('bdb53c41-7a9c-4101-8eac-4af6c1a643ba', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.598488+00', '2026-10-20 09:42:15.945402+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0030', 'Restaurante Ramos 30', 'BODEGA', 120.1, '2026-09-10', NULL, NULL, 'Aprobada el 2026-10-20', '00122049-9609-495d-84c4-f526d8cc0cee', 'fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('df7c462b-84f2-4283-899f-78e434e54c87', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.909125+00', '2026-10-20 09:42:14.956427+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0034', 'Ferretería Vargas 34', 'OFICINA', 288.8, '2026-09-22', NULL, NULL, NULL, 'c4551f79-e2fa-4de7-a374-59e6ae3ea37c', '8c717ce7-5524-4560-99bd-5b02c47f3fce', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('c9ef1c08-1768-4d35-8409-377d6225deb7', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.76014+00', '2026-10-20 09:42:15.909615+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0032', 'Botica Vargas 32', 'FARMACIA', 218.9, '2026-09-16', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', 'Aprobada el 2026-10-20', 'd7cbcddf-68ca-42a4-b3b3-00f5ce99a573', 'fc88650a-e9dc-4122-9530-9466e16c5394', 'aprobada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('3adf714a-ee09-4472-be0a-23ce2ce93250', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:14.993045+00', '2026-10-20 09:42:15.039098+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0035', 'Taller Salazar 35', 'TALLER', 119.8, '2026-09-25', NULL, NULL, NULL, '50ee49d8-325e-4564-ae56-a5de0709a456', '3a7aa199-1854-4416-97be-4a329cbc3c78', 'rechazada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('08afe0b4-153d-42fd-9aa7-ee1d572e71f4', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:15.076256+00', '2026-10-20 09:42:15.095948+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0036', 'Estudio Vargas 36', 'BODEGA', 193.8, '2026-09-01', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, '79067a68-4339-4d4b-a7b2-f2c06f9ebfa9', 'd1548202-a8ae-4a63-9196-4f8894264a7b', 'en_revision');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('c0ade110-eb76-4242-ade8-7980dd974212', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:15.344566+00', '2026-10-20 09:42:15.344566+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0038', 'Estudio Flores 38', 'FARMACIA', 179.4, '2026-10-07', NULL, NULL, NULL, 'a81f7da9-9ec6-4621-9615-bb97ede93564', 'e74d2236-a0bc-4942-8523-73dd76995381', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('829736de-17e2-4393-8925-eff86e054979', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:15.385946+00', '2026-10-20 09:42:15.433438+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0039', 'Ferretería Mamani 39', 'FERRETERIA', 73.3, '2026-10-10', NULL, NULL, NULL, '12f034e4-6351-4fa7-aca0-bfef1afb6f29', '4e105a70-c0fc-46c9-8d80-51532fa3ac8c', 'observada');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('82137858-4cd9-4f94-9a71-4f49f281ffb0', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:15.585023+00', '2026-10-20 09:42:15.585023+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0040', 'Taller Vargas 40', 'OFICINA', 229.2, '2026-10-13', NULL, 'Presentar plano de distribución actualizado y certificado de defensa civil.', NULL, '4a5537fa-da47-45c1-ae1c-c3a96d52c2c1', 'a26ddbbf-828d-4d0e-aa38-c46298053f2d', 'borrador');
INSERT INTO "app_data"."licencia__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "fecha_inspeccion", "observaciones", "resultado", "solicitante", "predio", "workflow_state") VALUES ('0e114cf0-753a-4be5-ba62-0ff1ede9709a', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:15.135396+00', '2026-10-20 09:42:15.865957+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'LF-2026-0037', 'Estudio Ramos 37', 'RESTAURANTE', 59.2, '2026-10-04', NULL, NULL, 'Aprobada el 2026-10-20', '7b2b6529-6c81-444f-950d-1635e123803d', '227e7b9c-ed20-40f3-9d97-c38c75f06eb8', 'aprobada');


--
-- Data for Name: predio__00000000; Type: TABLE DATA; Schema: app_data; Owner: -
--

INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('a5f07856-8281-4392-9a55-720ac56fe5fe', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.286279+00', '2026-10-20 09:42:10.286279+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0001', 'Calle Los Pinos 749, Distrito Demo', 517.43, 'RESIDENCIAL', 214470, '0103000020E61000000100000005000000CB48BDA7724353C04165FCFB8C2328C058AEB7CD544353C04165FCFB8C2328C058AEB7CD544353C0A391CF2B9E2228C0CB48BDA7724353C0A391CF2B9E2228C0CB48BDA7724353C04165FCFB8C2328C0', '846dbc52-4c85-4b69-a752-a035c14add2a');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('ad910484-8b39-4f3f-8195-7882b6076d87', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.349121+00', '2026-10-20 09:42:10.349121+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0002', 'Jr. Huallaga 439, Distrito Demo', 746.24, 'INDUSTRIAL', 832299, '0103000020E610000001000000050000001A1538D9064353C005C4245CC82328C0F0F96184F04253C005C4245CC82328C0F0F96184F04253C0B3EA73B5152328C01A1538D9064353C0B3EA73B5152328C01A1538D9064353C005C4245CC82328C0', 'c4551f79-e2fa-4de7-a374-59e6ae3ea37c');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('8c717ce7-5524-4560-99bd-5b02c47f3fce', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.394492+00', '2026-10-20 09:42:10.394492+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0003', 'Calle Los Pinos 357, Distrito Demo', 1354.36, 'RESIDENCIAL', 1060325, '0103000020E61000000100000005000000A4FCA4DAA74253C05E85949F542328C0FC54151A884253C05E85949F542328C0FC54151A884253C05D37A5BC562228C0A4FCA4DAA74253C05D37A5BC562228C0A4FCA4DAA74253C05E85949F542328C0', 'ed8caf7c-8bfe-474f-872b-46b7b39f2454');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('5f17835c-6d2f-4b5b-b5e5-341ccb3a574f', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.438907+00', '2026-10-20 09:42:10.438907+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0004', 'Jr. Ica 102, Distrito Demo', 964.09, 'RESIDENCIAL', 580952, '0103000020E610000001000000050000006D003620424253C0889D29745E2328C02FDB4E5B234253C0889D29745E2328C02FDB4E5B234253C0DC627E6E682228C06D003620424253C0DC627E6E682228C06D003620424253C0889D29745E2328C0', 'a81f7da9-9ec6-4621-9615-bb97ede93564');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('2c0c3d6c-c603-4757-8a69-ceaac3546e64', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.477785+00', '2026-10-20 09:42:10.477785+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0005', 'Jr. Huallaga 605, Distrito Demo', 507.75, 'RESIDENCIAL', 535148, '0103000020E61000000100000005000000D4D7F335CB4353C03A02B859BC2028C0B7EC10FFB04353C03A02B859BC2028C0B7EC10FFB04353C058A9A0A2EA1F28C0D4D7F335CB4353C058A9A0A2EA1F28C0D4D7F335CB4353C03A02B859BC2028C0', '16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('3a7aa199-1854-4416-97be-4a329cbc3c78', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.543881+00', '2026-10-20 09:42:10.543881+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0006', 'Calle Las Gardenias 643, Distrito Demo', 1770.61, 'COMERCIAL', 1328326, '0103000020E61000000100000005000000E94482A9664353C0A7954220972028C0D89FC4E74E4353C0A7954220972028C0D89FC4E74E4353C01A6D5512D91F28C0E94482A9664353C01A6D5512D91F28C0E94482A9664353C0A7954220972028C0', 'f7f597ae-3131-4f67-9a98-693c6dbaf031');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('a9f7e251-496c-44f1-9f40-12f253554622', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.586998+00', '2026-10-20 09:42:10.586998+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0007', 'Av. El Sol 699, Distrito Demo', 1772.56, 'COMERCIAL', 1137952, '0103000020E610000001000000050000009293895B054353C0A164726A672028C004013274EC4253C0A164726A672028C004013274EC4253C031D0B52FA01F28C09293895B054353C031D0B52FA01F28C09293895B054353C0A164726A672028C0', '68d95c39-57f5-4d1f-8595-153df4f76aa5');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('d1f180c9-1e14-4559-93dd-75697358f83d', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.642521+00', '2026-10-20 09:42:10.642521+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0008', 'Jr. Las Begonias 298, Distrito Demo', 960.08, 'RESIDENCIAL', 361546, '0103000020E61000000100000005000000F90FE9B7AF4253C0859675FF582028C0236937FA984253C0859675FF582028C0236937FA984253C0D05FE811A31F28C0F90FE9B7AF4253C0D05FE811A31F28C0F90FE9B7AF4253C0859675FF582028C0', 'aac7c6c4-5c4f-4228-8a96-4786e71ccf9e');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('d1548202-a8ae-4a63-9196-4f8894264a7b', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.697175+00', '2026-10-20 09:42:10.697175+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0009', 'Jr. Huallaga 967, Distrito Demo', 117.55, 'COMERCIAL', 43085, '0103000020E6100000010000000500000048C2BE9D444253C0CC99ED0A7D2028C00531D0B52F4253C0CC99ED0A7D2028C00531D0B52F4253C0B60F79CBD51F28C048C2BE9D444253C0B60F79CBD51F28C048C2BE9D444253C0CC99ED0A7D2028C0', '7c9fb903-a304-4e72-8f48-9bd9340a4d82');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('f579c281-f200-40e1-96cd-8deaa7f0e553', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.745759+00', '2026-10-20 09:42:10.745759+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0010', 'Calle Los Pinos 762, Distrito Demo', 742.57, 'COMERCIAL', 270433, '0103000020E61000000100000005000000FBCE2F4AD04353C0A6B5696CAF1D28C0001E51A1BA4353C0A6B5696CAF1D28C0001E51A1BA4353C0CF2D7425021D28C0FBCE2F4AD04353C0CF2D7425021D28C0FBCE2F4AD04353C0A6B5696CAF1D28C0', '2f24c06a-1df3-4791-8330-56897fa5a0d0');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('540f8841-1d8c-47e7-b126-ecbbcddc2b1e', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.789039+00', '2026-10-20 09:42:10.789039+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0011', 'Av. Los Próceres 684, Distrito Demo', 1516.68, 'RESIDENCIAL', 1352004, '0103000020E610000001000000050000000E6954E0644353C0737FF5B86F1D28C0E7374C34484353C0737FF5B86F1D28C0E7374C34484353C03CF6B3588A1C28C00E6954E0644353C03CF6B3588A1C28C00E6954E0644353C0737FF5B86F1D28C0', '7b2b6529-6c81-444f-950d-1635e123803d');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('227e7b9c-ed20-40f3-9d97-c38c75f06eb8', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.841061+00', '2026-10-20 09:42:10.841061+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0012', 'Jr. Las Begonias 621, Distrito Demo', 943.27, 'RESIDENCIAL', 631574, '0103000020E61000000100000005000000CD77F013074353C049A12C7C7D1D28C07AFD497CEE4253C049A12C7C7D1D28C07AFD497CEE4253C0B3CEF8BEB81C28C0CD77F013074353C0B3CEF8BEB81C28C0CD77F013074353C049A12C7C7D1D28C0', '8e6d1243-a7c5-4aac-9b83-88b463337076');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.883743+00', '2026-10-20 09:42:10.883743+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0013', 'Jr. Ica 667, Distrito Demo', 535.14, 'RESIDENCIAL', 264870, '0103000020E61000000100000005000000E76D6C76A44253C09087BEBB951D28C01349F4328A4253C09087BEBB951D28C01349F4328A4253C0E960FD9FC31C28C0E76D6C76A44253C0E960FD9FC31C28C0E76D6C76A44253C09087BEBB951D28C0', '63b995af-c0ef-4290-8981-eab21020a4e4');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('3b211ef7-e67f-4a3d-9a0b-12979deb7cde', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.930236+00', '2026-10-20 09:42:10.930236+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0014', 'Calle Las Gardenias 530, Distrito Demo', 781.83, 'INDUSTRIAL', 778204, '0103000020E61000000100000005000000D316D7F84C4253C02AAC5450511D28C0D3DC0A61354253C02AAC5450511D28C0D3DC0A61354253C0EAEC6470941C28C0D316D7F84C4253C0EAEC6470941C28C0D316D7F84C4253C02AAC5450511D28C0', '5c59a1f5-97ae-45cf-9ef3-f142f8a41f75');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('e74d2236-a0bc-4942-8523-73dd76995381', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:10.976466+00', '2026-10-20 09:42:10.976466+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0015', 'Av. Los Próceres 735, Distrito Demo', 1972.1, 'COMERCIAL', 1491161, '0103000020E61000000100000005000000D8463CD9CD4353C038F92D3A591A28C082AB3C81B04353C038F92D3A591A28C082AB3C81B04353C0861E317A6E1928C0D8463CD9CD4353C0861E317A6E1928C0D8463CD9CD4353C038F92D3A591A28C0', '170f415c-27ce-4b59-9acb-027585d7f293');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.023513+00', '2026-10-20 09:42:11.023513+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0016', 'Av. El Sol 726, Distrito Demo', 292.98, 'COMERCIAL', 232678, '0103000020E610000001000000050000001329CDE6714353C0738236397C1A28C04B00FE29554353C0738236397C1A28C04B00FE29554353C0772B4B74961928C01329CDE6714353C0772B4B74961928C01329CDE6714353C0738236397C1A28C0', 'd7cbcddf-68ca-42a4-b3b3-00f5ce99a573');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('4a6042dc-228f-4671-b0e3-7f340b43e685', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.069674+00', '2026-10-20 09:42:11.069674+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0017', 'Calle Las Gardenias 853, Distrito Demo', 1290.66, 'COMERCIAL', 1033290, '0103000020E61000000100000005000000CDAE7B2B124353C013471E882C1A28C0981936CAFA4253C013471E882C1A28C0981936CAFA4253C0679DF17D711928C0CDAE7B2B124353C0679DF17D711928C0CDAE7B2B124353C013471E882C1A28C0', '2d8fbbd8-3413-465c-a318-596a8b8c2989');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('4e105a70-c0fc-46c9-8d80-51532fa3ac8c', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.112643+00', '2026-10-20 09:42:11.112643+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0018', 'Jr. Huallaga 718, Distrito Demo', 332.08, 'RESIDENCIAL', 118655, '0103000020E61000000100000005000000F17F4754A84253C0B5E0455F411A28C0EE0A7DB08C4253C0B5E0455F411A28C0EE0A7DB08C4253C09738F240641928C0F17F4754A84253C09738F240641928C0F17F4754A84253C0B5E0455F411A28C0', '79067a68-4339-4d4b-a7b2-f2c06f9ebfa9');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('766b8f85-b5e6-4870-abe2-e6677e1e9bc5', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.155792+00', '2026-10-20 09:42:11.155792+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0019', 'Calle Las Gardenias 401, Distrito Demo', 534.78, 'RESIDENCIAL', 459436, '0103000020E6100000010000000500000045F295404A4253C043041C42951A28C0EAE74D452A4253C043041C42951A28C0EAE74D452A4253C06CB1DB67951928C045F295404A4253C06CB1DB67951928C045F295404A4253C043041C42951A28C0', '21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('ba9b3af8-e337-4d4d-8de6-e1b8f926b06d', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.197143+00', '2026-10-20 09:42:11.197143+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0020', 'Calle Los Pinos 398, Distrito Demo', 443.38, 'COMERCIAL', 181879, '0103000020E6100000010000000500000022E010AAD44353C0B43A3943711728C0A0FB7266BB4353C0B43A3943711728C0A0FB7266BB4353C0A5164A26A71628C022E010AAD44353C0A5164A26A71628C022E010AAD44353C0B43A3943711728C0', '4a5537fa-da47-45c1-ae1c-c3a96d52c2c1');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('a26ddbbf-828d-4d0e-aa38-c46298053f2d', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.237456+00', '2026-10-20 09:42:11.237456+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0021', 'Av. El Sol 604, Distrito Demo', 1930.83, 'RESIDENCIAL', 821033, '0103000020E610000001000000050000004E27D9EA724353C082CAF8F7191728C096E7C1DD594353C082CAF8F7191728C096E7C1DD594353C0C4CC3E8F511628C04E27D9EA724353C0C4CC3E8F511628C04E27D9EA724353C082CAF8F7191728C0', 'fce2430d-6b2d-4c23-a75d-45ddf38ff015');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('fc88650a-e9dc-4122-9530-9466e16c5394', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.274725+00', '2026-10-20 09:42:11.274725+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0022', 'Jr. Huallaga 522, Distrito Demo', 1279.64, 'RESIDENCIAL', 1141856, '0103000020E6100000010000000500000093FE5E0A0F4353C04BC972124A1728C006A051BAF44253C04BC972124A1728C006A051BAF44253C0E1D40792771628C093FE5E0A0F4353C0E1D40792771628C093FE5E0A0F4353C04BC972124A1728C0', '5735071c-f421-4bf8-801a-ab912ab8186f');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('73cb1aff-421a-4366-a9da-e990f6e815ee', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.320064+00', '2026-10-20 09:42:11.320064+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0023', 'Jr. Huallaga 590, Distrito Demo', 1178.95, 'INDUSTRIAL', 1135897, '0103000020E6100000010000000500000009A87004A94253C095D6DF12801728C0349D9D0C8E4253C095D6DF12801728C0349D9D0C8E4253C0F17F4754A81628C009A87004A94253C0F17F4754A81628C009A87004A94253C095D6DF12801728C0', '16b55360-6e5a-4721-acb4-7ea60bec232d');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('46222881-cddc-4be0-874c-331c2c07cdb0', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.368479+00', '2026-10-20 09:42:11.368479+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0024', 'Av. Los Próceres 650, Distrito Demo', 597.14, 'COMERCIAL', 270557, '0103000020E61000000100000005000000CEA78E554A4253C05F44DB31751728C0780C8FFD2C4253C05F44DB31751728C0780C8FFD2C4253C0AD69DE718A1628C0CEA78E554A4253C0AD69DE718A1628C0CEA78E554A4253C05F44DB31751728C0', '4bf492c9-9874-443c-9f24-74d0643acb92');
INSERT INTO "app_data"."predio__00000000" ("id", "organization_id", "created_at", "updated_at", "created_by", "updated_by", "codigo", "direccion", "area", "uso", "valor_autovaluo", "lote", "titular") VALUES ('a84b0be0-62c8-4ff6-b3a6-54857c5218b0', '00000000-0000-0000-0000-000000000001', '2026-10-20 09:42:11.419165+00', '2026-10-20 09:42:11.419165+00', '00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100', 'P-0025', 'Calle Las Gardenias 406, Distrito Demo', 501.33, 'INDUSTRIAL', 361940, '0103000020E6100000010000000500000028B7ED7BD44353C095F1EF332E1428C0B6847CD0B34353C095F1EF332E1428C0B6847CD0B34353C0035E66D8281328C028B7ED7BD44353C0035E66D8281328C028B7ED7BD44353C095F1EF332E1428C0', '50ee49d8-325e-4564-ae56-a5de0709a456');


--
-- Data for Name: spatial_ref_sys; Type: TABLE DATA; Schema: public; Owner: -
--



--
-- Data for Name: audit_log; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('01294551-ba58-4c22-8a5f-a03cadfc5485', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:object', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'CREATE', NULL, '{"name": "contribuyente", "label": "Contribuyente", "fields": [{"name": "codigo", "type": "TEXT", "label": "Código", "unique": true, "indexed": false, "visible": true, "editable": true, "position": 0, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "nombre", "type": "TEXT", "label": "Nombre o razón social", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 1, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "tipo", "type": "ENUM", "label": "Tipo", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 2, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": ["PERSONA_NATURAL", "PERSONA_JURIDICA"], "defaultValue": null, "relationTarget": null}, {"name": "documento", "type": "TEXT", "label": "DNI / RUC", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 3, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "email", "type": "EMAIL", "label": "Correo", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 4, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "telefono", "type": "TEXT", "label": "Teléfono", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 5, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "direccion", "type": "TEXT", "label": "Dirección fiscal", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 6, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "activo", "type": "BOOLEAN", "label": "Activo", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 7, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": "true", "relationTarget": null}], "actions": {}, "apiOnly": false, "enabled": true, "indexes": [], "appendOnly": false, "description": "Persona natural o jurídica con obligaciones municipales", "pluralLabel": "Contribuyentes", "physicalTable": "contribuyente__00000000", "requiresReason": false, "uniqueConstraints": []}', '2026-10-20 09:42:04.320986+00', NULL, NULL, '33c9fb43-e9c1-4106-9987-24168588b6ac', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d43fb63b-f82c-4039-ba15-01d2954b1881', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:object', 'd4333a27-6895-4ced-8cca-808a5c439298', 'CREATE', NULL, '{"name": "predio", "label": "Predio", "fields": [{"name": "codigo", "type": "TEXT", "label": "Código catastral", "unique": true, "indexed": false, "visible": true, "editable": true, "position": 0, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "direccion", "type": "TEXT", "label": "Dirección", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 1, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "area", "type": "DECIMAL", "label": "Área (m²)", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 2, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "uso", "type": "ENUM", "label": "Uso", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 3, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": ["RESIDENCIAL", "COMERCIAL", "INDUSTRIAL", "AGRICOLA"], "defaultValue": null, "relationTarget": null}, {"name": "valor_autovaluo", "type": "DECIMAL", "label": "Valor de autovalúo (S/)", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 4, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "lote", "type": "GEOMETRY", "label": "Lote", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 5, "required": false, "timeZone": null, "attributes": {"srid": 4326, "dimension": 2, "geometry_type": "POLYGON", "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}], "actions": {}, "apiOnly": false, "enabled": true, "indexes": [], "appendOnly": false, "description": "Unidad catastral del distrito", "pluralLabel": "Predios", "physicalTable": "predio__00000000", "requiresReason": false, "uniqueConstraints": []}', '2026-10-20 09:42:05.011297+00', NULL, NULL, 'c21cc8b7-7c34-4dfb-8f8d-db7be1523ed9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ffe3d9a8-df22-4649-99a2-69596a5de40e', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:object', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'CREATE', NULL, '{"name": "licencia", "label": "Licencia de funcionamiento", "fields": [{"name": "codigo", "type": "TEXT", "label": "Expediente", "unique": true, "indexed": false, "visible": true, "editable": true, "position": 0, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "razon_social", "type": "TEXT", "label": "Establecimiento", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 1, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "giro", "type": "ENUM", "label": "Giro", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 2, "required": true, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": ["BODEGA", "RESTAURANTE", "FARMACIA", "FERRETERIA", "OFICINA", "TALLER"], "defaultValue": null, "relationTarget": null}, {"name": "area_local", "type": "DECIMAL", "label": "Área del local (m²)", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 3, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "fecha_solicitud", "type": "DATE", "label": "Fecha de solicitud", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 4, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "fecha_inspeccion", "type": "DATETIME", "label": "Inspección", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 5, "required": false, "timeZone": "America/Lima", "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "observaciones", "type": "LONG_TEXT", "label": "Observaciones", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 6, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}, {"name": "resultado", "type": "TEXT", "label": "Resultado", "unique": false, "indexed": false, "visible": true, "editable": true, "position": 7, "required": false, "timeZone": null, "attributes": {"srid": null, "dimension": null, "geometry_type": null, "file_max_bytes": null, "file_content_types": null}, "description": null, "enumOptions": null, "defaultValue": null, "relationTarget": null}], "actions": {}, "apiOnly": false, "enabled": true, "indexes": [], "appendOnly": false, "description": "Solicitud de licencia para un establecimiento comercial", "pluralLabel": "Licencias de funcionamiento", "physicalTable": "licencia__00000000", "requiresReason": true, "uniqueConstraints": []}', '2026-10-20 09:42:05.192478+00', NULL, NULL, 'e450e3d1-f922-40f3-9816-7c6f16ff336d', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('59333b0c-a675-4d65-b37a-4abbb1887bc3', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:relationship', '2b4ea0ba-a794-4fd9-8606-e7106c1133e6', 'CREATE', NULL, '{"name": "predio_titular", "type": "MANY_TO_ONE", "label": "Titular", "source": "predio", "target": "contribuyente", "fieldName": "titular", "joinTable": null, "inverseLabel": "Predios"}', '2026-10-20 09:42:05.375933+00', NULL, NULL, '9208a061-ca76-4b24-9a78-806924f350e9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6f8516c4-a7ac-4733-bc02-08dd9c06d36f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:relationship', '8cdf9279-e7fa-4d49-82b7-f7685c466fc0', 'CREATE', NULL, '{"name": "licencia_solicitante", "type": "MANY_TO_ONE", "label": "Solicitante", "source": "licencia", "target": "contribuyente", "fieldName": "solicitante", "joinTable": null, "inverseLabel": "Licencias"}', '2026-10-20 09:42:05.512014+00', NULL, NULL, '9b02994d-5aee-411d-a7c9-cb517613c3b1', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('295c81ac-16c4-4971-9cf6-8f97889f2a7f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:relationship', 'adde0827-6b2a-4f42-b08c-096689c8ffd5', 'CREATE', NULL, '{"name": "licencia_predio", "type": "MANY_TO_ONE", "label": "Predio", "source": "licencia", "target": "predio", "fieldName": "predio", "joinTable": null, "inverseLabel": "Licencias"}', '2026-10-20 09:42:05.642511+00', NULL, NULL, '29711b5b-8ddf-40b9-b741-c96b6a50746a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('e019b479-1138-4595-a7a0-5324402117fb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:role', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'CREATE', NULL, '{"name": "REGISTRADOR", "label": "Registrador de licencias", "permissions": {}, "ownRecordsOnly": false, "fieldPermissions": {}}', '2026-10-20 09:42:05.837591+00', NULL, NULL, '9275ec1c-2820-4844-a768-a5dc534e6ea6', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('825177e0-289b-4af5-9483-1dc41ad4dc8e', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:permission', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'UPDATE', '{"role": "REGISTRADOR"}', '{"role": "REGISTRADOR", "predio.READ": true, "licencia.READ": true, "licencia.CREATE": true, "licencia.UPDATE": true, "contribuyente.READ": true, "contribuyente.CREATE": true, "contribuyente.UPDATE": true}', '2026-10-20 09:42:05.915198+00', NULL, NULL, 'a38c1bed-bdfd-41ad-95c7-1468679dfc45', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('33232240-649c-4572-ba4f-def30688f3f5', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:role', 'd5d5575b-017f-451d-8b7b-cb958619e12a', 'CREATE', NULL, '{"name": "REVISOR", "label": "Revisor técnico", "permissions": {}, "ownRecordsOnly": false, "fieldPermissions": {}}', '2026-10-20 09:42:06.012442+00', NULL, NULL, '7e81ef36-5148-4e31-9924-6b703405cb38', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('34100852-7aa1-458d-bab3-b3686c8ab098', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:permission', 'd5d5575b-017f-451d-8b7b-cb958619e12a', 'UPDATE', '{"role": "REVISOR"}', '{"role": "REVISOR", "predio.READ": true, "licencia.READ": true, "licencia.UPDATE": true, "contribuyente.READ": true}', '2026-10-20 09:42:06.054557+00', NULL, NULL, 'fa943025-30da-4aa8-9488-f7e8c6b8b2ef', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5cb86f39-38f5-4d79-ae8a-784902af289b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:role', '1cbd090a-8508-442a-9c20-3d53077e7050', 'CREATE', NULL, '{"name": "CONSULTA", "label": "Consulta", "permissions": {}, "ownRecordsOnly": false, "fieldPermissions": {}}', '2026-10-20 09:42:06.101807+00', NULL, NULL, 'b561f0b3-9c31-4b83-805c-15da7af8d41e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ab36cc13-75e5-42f6-b78a-dd365cb71a84', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:permission', '1cbd090a-8508-442a-9c20-3d53077e7050', 'UPDATE', '{"role": "CONSULTA"}', '{"role": "CONSULTA", "predio.READ": true, "licencia.READ": true, "contribuyente.READ": true}', '2026-10-20 09:42:06.134667+00', NULL, NULL, '208ab81e-f7c8-4f76-ad14-76148872d5ed', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('79aa4ef8-b4d5-405f-aee7-2a11d7e7fa89', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:permission', '1cbd090a-8508-442a-9c20-3d53077e7050', 'UPDATE', '{"role": "CONSULTA"}', '{"role": "CONSULTA", "contribuyente.telefono": {"read": false, "write": false}, "contribuyente.documento": {"read": false, "write": false}}', '2026-10-20 09:42:06.220206+00', NULL, NULL, '1f851d00-e757-46f1-b629-d2f01597f89c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f6730f16-e968-4792-ae76-fbff6c6847c9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:org-unit', '6cc217f3-7fbc-492d-a7d0-bd30007fcd56', 'CREATE', NULL, '{"code": "GDE", "label": "Gerencia de Desarrollo Económico", "parentCode": null}', '2026-10-20 09:42:06.344221+00', NULL, NULL, 'be1e5f43-b82b-4458-97bd-14b9323e895b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b8f35a27-660b-4ac5-b28d-2916f6030606', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:org-unit', '0865612c-a187-4ffc-be93-9978ade56ecd', 'CREATE', NULL, '{"code": "SGLC", "label": "Subgerencia de Licencias Comerciales", "parentCode": "GDE"}', '2026-10-20 09:42:06.393204+00', NULL, NULL, '7cea3fd1-64f4-4957-ae5e-1eafa293128b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('45231f38-e0a1-4f93-baba-4a1482581e55', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', 'b2556764-05cc-416e-af0e-ec0313b72e5c', 'CREATE', NULL, '{"email": "registrador@demo.example", "roles": ["REGISTRADOR"], "enabled": true, "orgUnits": [], "displayName": "Rosa Registradora"}', '2026-10-20 09:42:06.51018+00', NULL, NULL, '3c8b805f-20f8-4a97-b7b5-635daa8fbf59', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('35afcdd3-b11c-44be-bd4d-05928376c861', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', 'b2556764-05cc-416e-af0e-ec0313b72e5c', 'UPDATE', '{"email": "registrador@demo.example", "roles": ["REGISTRADOR"], "enabled": true, "orgUnits": [], "displayName": "Rosa Registradora"}', '{"email": "registrador@demo.example", "roles": ["REGISTRADOR"], "enabled": true, "orgUnits": ["SGLC"], "displayName": "Rosa Registradora"}', '2026-10-20 09:42:06.643612+00', NULL, NULL, 'e0583eb3-ee80-4d11-9114-b99fee83e72a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('2e82b56c-0701-484a-8d64-e7f26d7df1ac', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', '5a574dae-3936-415d-a3fd-3fcabee18411', 'CREATE', NULL, '{"email": "revisor@demo.example", "roles": ["REVISOR"], "enabled": true, "orgUnits": [], "displayName": "Raúl Revisor"}', '2026-10-20 09:42:06.704659+00', NULL, NULL, 'a5daee02-4a58-4ec6-9948-70c7b8cb179b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('471cb290-4853-40d9-a412-474d42467922', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', '5a574dae-3936-415d-a3fd-3fcabee18411', 'UPDATE', '{"email": "revisor@demo.example", "roles": ["REVISOR"], "enabled": true, "orgUnits": [], "displayName": "Raúl Revisor"}', '{"email": "revisor@demo.example", "roles": ["REVISOR"], "enabled": true, "orgUnits": ["SGLC"], "displayName": "Raúl Revisor"}', '2026-10-20 09:42:06.813505+00', NULL, NULL, '18805722-0b09-45e3-93a8-4b6b8465beb2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('69ccb472-4698-4462-a477-96f1ad1815bc', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', '1c2668cf-c06f-4855-8ea9-8454ee0272cc', 'CREATE', NULL, '{"email": "consulta@demo.example", "roles": ["CONSULTA"], "enabled": true, "orgUnits": [], "displayName": "Carmen Consulta"}', '2026-10-20 09:42:06.861794+00', NULL, NULL, '86f6c416-b2b0-43a1-8419-5ddcec13844e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('61cd154e-9969-4fe5-921c-2ed2cecd9fb5', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:user', '1c2668cf-c06f-4855-8ea9-8454ee0272cc', 'UPDATE', '{"email": "consulta@demo.example", "roles": ["CONSULTA"], "enabled": true, "orgUnits": [], "displayName": "Carmen Consulta"}', '{"email": "consulta@demo.example", "roles": ["CONSULTA"], "enabled": true, "orgUnits": ["GDE"], "displayName": "Carmen Consulta"}', '2026-10-20 09:42:06.992664+00', NULL, NULL, '38d1f085-9d68-4c9e-83fc-68a3482c1d53', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('22fb08f4-3399-4d10-a594-aefd983747fc', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'admin:service-account', '04046ce4-125c-4bde-a60b-2609fbfcae39', 'CREATE', NULL, '{"name": "integracion-rentas", "roles": ["CONSULTA"], "enabled": true}', '2026-10-20 09:42:07.082344+00', NULL, NULL, '6f651e5f-b56a-4df6-ac24-83cedcc3a659', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('4e47390f-1e0c-4e11-b520-9387f1ab9217', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '00122049-9609-495d-84c4-f526d8cc0cee', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "ana.caceres.castillo@correo.example", "activo": true, "codigo": "C-0001", "nombre": "Ana Cáceres Castillo", "telefono": "919605159", "direccion": "Av. El Sol 865, Distrito Demo", "documento": "53470582"}', '2026-10-20 09:42:08.813537+00', NULL, NULL, 'b0972629-9d1d-49e5-a012-e356d7a9b82f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d79888b4-3dd4-4c9f-b8aa-988a46734975', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '63b995af-c0ef-4290-8981-eab21020a4e4', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "pedro.torres.paredes@correo.example", "activo": true, "codigo": "C-0002", "nombre": "Pedro Torres Paredes", "telefono": "902577483", "direccion": "Av. Los Próceres 193, Distrito Demo", "documento": "76581643"}', '2026-10-20 09:42:08.890282+00', NULL, NULL, '52cc8f27-b368-4a10-bd2d-c073efb2e853', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('eea98009-0069-43ce-b041-b9cb4a6d6ced', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '26e36d67-2d3a-48c0-8569-aa97fa272bad', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "comercial.andina.s.a.c@correo.example", "activo": true, "codigo": "C-0003", "nombre": "Comercial Andina S.A.C.", "telefono": "958476538", "direccion": "Jr. Las Begonias 561, Distrito Demo", "documento": "20198737142"}', '2026-10-20 09:42:08.954535+00', NULL, NULL, 'b19a0c90-d091-4c26-8b7f-2dcecc7099cf', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('06c4bd24-6257-426c-b0d0-dbab15f8657f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '7c9fb903-a304-4e72-8f48-9bd9340a4d82', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "jose.flores.paredes@correo.example", "activo": true, "codigo": "C-0004", "nombre": "José Flores Paredes", "telefono": "976430319", "direccion": "Jr. Las Begonias 330, Distrito Demo", "documento": "39768829"}', '2026-10-20 09:42:09.001987+00', NULL, NULL, '83e2fec6-fcac-438e-a160-7e4c1aa54db8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6061b1df-890b-4076-911f-19fedea9511c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '5735071c-f421-4bf8-801a-ab912ab8186f', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "sofia.mamani.vargas@correo.example", "activo": true, "codigo": "C-0005", "nombre": "Sofía Mamani Vargas", "telefono": "988196682", "direccion": "Calle Las Gardenias 359, Distrito Demo", "documento": "49276830"}', '2026-10-20 09:42:09.056879+00', NULL, NULL, '21a802fe-9b28-432f-b1fe-1f165a481bc5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('16e23430-3b62-4354-aee1-fef355e1615c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "inversiones.rimac.s.a.c@correo.example", "activo": true, "codigo": "C-0006", "nombre": "Inversiones Rímac S.A.C.", "telefono": "946024059", "direccion": "Jr. Las Begonias 434, Distrito Demo", "documento": "20588030075"}', '2026-10-20 09:42:09.113744+00', NULL, NULL, 'b55acc3b-83ff-4f61-a918-8291ae11c005', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('9c20b986-2cea-462c-a46e-102385a1a141', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '79067a68-4339-4d4b-a7b2-f2c06f9ebfa9', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "maria.quispe.chavez@correo.example", "activo": true, "codigo": "C-0007", "nombre": "María Quispe Chávez", "telefono": "975292336", "direccion": "Av. Primavera 635, Distrito Demo", "documento": "63440664"}', '2026-10-20 09:42:09.178958+00', NULL, NULL, 'd47e7f6b-24c5-4e22-b6cf-1ad16f58f339', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5058c5c0-6c0a-4afb-aa46-ee4d4e1faff2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '846dbc52-4c85-4b69-a752-a035c14add2a', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "lucia.salazar.huaman@correo.example", "activo": true, "codigo": "C-0008", "nombre": "Lucía Salazar Huamán", "telefono": "986069130", "direccion": "Calle Las Gardenias 696, Distrito Demo", "documento": "91261268"}', '2026-10-20 09:42:09.240423+00', NULL, NULL, 'e96acd97-bded-463d-8b22-58d815845426', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('23affd7a-571c-410c-bf6e-ecac26cdec37', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '5c59a1f5-97ae-45cf-9ef3-f142f8a41f75', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "servicios.pachacutec.s.a@correo.example", "activo": true, "codigo": "C-0009", "nombre": "Servicios Pachacútec S.A.C.", "telefono": "951383995", "direccion": "Av. Primavera 528, Distrito Demo", "documento": "20712579845"}', '2026-10-20 09:42:09.292195+00', NULL, NULL, 'ea3742be-4eee-4914-9ad0-73b9fb2fe21f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ab782ca3-dadc-4281-b656-7b92a6e1f149', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '12f034e4-6351-4fa7-aca0-bfef1afb6f29', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "jorge.rojas.caceres@correo.example", "activo": true, "codigo": "C-0010", "nombre": "Jorge Rojas Cáceres", "telefono": "950711371", "direccion": "Av. Primavera 458, Distrito Demo", "documento": "13302271"}', '2026-10-20 09:42:09.34396+00', NULL, NULL, 'b43beab9-7ab2-4201-8b91-4ce599c454b5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('0c56760f-13de-41b9-a703-db68747dcb0f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '2f24c06a-1df3-4791-8330-56897fa5a0d0', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "sofia.torres.vargas@correo.example", "activo": false, "codigo": "C-0011", "nombre": "Sofía Torres Vargas", "telefono": "916509497", "direccion": "Av. Los Próceres 882, Distrito Demo", "documento": "92784996"}', '2026-10-20 09:42:09.394506+00', NULL, NULL, 'c135d6c3-0a6a-4751-9d66-ce357de2654a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('9c04b267-d1e2-4b4f-bd8c-d2664d9fd261', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '16b55360-6e5a-4721-acb4-7ea60bec232d', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "distribuidora.los.olivos@correo.example", "activo": true, "codigo": "C-0012", "nombre": "Distribuidora Los Olivos S.A.C.", "telefono": "963973882", "direccion": "Av. Primavera 436, Distrito Demo", "documento": "20214096236"}', '2026-10-20 09:42:09.432254+00', NULL, NULL, 'ab56f4a1-0b2b-46ee-b746-614e8e41b244', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f682d7d2-e218-468e-8ce4-ae4b4a826fbe', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'f7f597ae-3131-4f67-9a98-693c6dbaf031', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "miguel.salazar.salazar@correo.example", "activo": true, "codigo": "C-0013", "nombre": "Miguel Salazar Salazar", "telefono": "994015895", "direccion": "Jr. Huallaga 706, Distrito Demo", "documento": "90530009"}', '2026-10-20 09:42:09.479897+00', NULL, NULL, '387cbd3d-f30b-40ab-abee-2eebe7e80ef4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b2fdf85e-0220-4f05-9ad3-41fdfbf26ead', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "elena.vargas.salazar@correo.example", "activo": true, "codigo": "C-0014", "nombre": "Elena Vargas Salazar", "telefono": "990082418", "direccion": "Av. El Sol 210, Distrito Demo", "documento": "50338974"}', '2026-10-20 09:42:09.533072+00', NULL, NULL, 'dd77aa9d-6f60-45dd-a633-abdf0be3ea26', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('2d19fd08-857d-42b9-8853-dec406a8a96f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'c4551f79-e2fa-4de7-a374-59e6ae3ea37c', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "grupo.miraflores.s.a.c@correo.example", "activo": true, "codigo": "C-0015", "nombre": "Grupo Miraflores S.A.C.", "telefono": "977424244", "direccion": "Calle Las Gardenias 127, Distrito Demo", "documento": "20113177719"}', '2026-10-20 09:42:09.580871+00', NULL, NULL, '8fdd14b6-6a98-4381-924c-210c6e529601', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d30ab49c-e18e-406a-819a-60ea20369684', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '170f415c-27ce-4b59-9acb-027585d7f293', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "teresa.flores.vargas@correo.example", "activo": true, "codigo": "C-0016", "nombre": "Teresa Flores Vargas", "telefono": "931307053", "direccion": "Av. El Sol 110, Distrito Demo", "documento": "54042236"}', '2026-10-20 09:42:09.620153+00', NULL, NULL, '3116e22a-053a-4f18-834b-549ac6ae3575', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('cc65b6b6-ada8-4b31-bdc8-d88260778509', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.720432+00', NULL, 'Carga de datos de demostración', 'bb36c078-ad5c-4544-9711-f51ebbf8bb7a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f52ed063-97dc-4b20-ba9f-68039fce0260', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:11.761449+00', NULL, 'Carga de datos de demostración', '681186e5-d773-421f-8ee1-a37b2a056651', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8645a881-a8f9-4ba1-abfd-95c70ca1ab52', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '5f31a5d3-a189-4023-933f-7f1df56de85c', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0003", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 53, "solicitante": "7c9fb903-a304-4e72-8f48-9bd9340a4d82", "razon_social": "Estudio Chávez 3", "observaciones": null, "fecha_solicitud": "2026-07-10", "fecha_inspeccion": null}', '2026-10-20 09:42:11.832988+00', NULL, 'Carga de datos de demostración', '43137323-6fd6-43e4-804f-3bab346e4d64', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b8638f41-82fd-4f13-b351-2db1fca0db35', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '5f31a5d3-a189-4023-933f-7f1df56de85c', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.847671+00', NULL, 'Carga de datos de demostración', '3a8b69bf-1225-479d-b60a-87cb7e746cc5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('68bc53be-17b1-41a6-88cb-6b8886657678', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}', '2026-10-20 09:42:11.900624+00', NULL, 'Carga de datos de demostración', '8331390d-93f5-4ef5-9995-115b84561f9b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('4dbb493b-e60b-471d-ae88-2c71a8b48fb8', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.924054+00', NULL, 'Carga de datos de demostración', 'ed8c7c55-cd00-4be7-ac3b-680d074ab672', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('4b345ae0-2e40-410c-a33c-4f753efc9a0c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:11.955464+00', NULL, 'Falta plano de distribución', '451821be-ec3e-4913-bdf0-9aebdffabb34', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('82870afb-88a4-420f-8de6-d5a87309e62d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.982583+00', NULL, 'Carga de datos de demostración', 'e203411e-b3dc-4910-94b7-49fb9a81ae4b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('aa44deeb-1820-4e7a-bce3-531f0a1db3ee', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:12.011235+00', NULL, 'Carga de datos de demostración', 'bf016f13-687b-4a5c-80bd-d8c30b243e0a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f36d07a6-488b-4b11-be3d-c6435a888363', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f3cd7c83-2a8f-4195-a15b-2f7b72959ef1', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0005", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 53.5, "solicitante": "50ee49d8-325e-4564-ae56-a5de0709a456", "razon_social": "Restaurante Huamán 5", "observaciones": null, "fecha_solicitud": "2026-07-16", "fecha_inspeccion": null}', '2026-10-20 09:42:12.070981+00', NULL, 'Carga de datos de demostración', 'e71b0872-03d5-41da-ad1b-87e03ad4d0e1', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('7282530c-ca45-44df-91cd-980781650b8a', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ecbe4879-eca3-4cf4-8f2f-25224eab99bd', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0006", "predio": "766b8f85-b5e6-4870-abe2-e6677e1e9bc5", "resultado": null, "area_local": 185.5, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Restaurante Vargas 6", "observaciones": null, "fecha_solicitud": "2026-07-19", "fecha_inspeccion": null}', '2026-10-20 09:42:12.11063+00', NULL, 'Carga de datos de demostración', 'd75690ab-ab06-41ee-bf48-400e57a5d25e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('af97cfdc-bacc-4128-81e8-19f4fe3d5702', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ecbe4879-eca3-4cf4-8f2f-25224eab99bd', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.131176+00', NULL, 'Carga de datos de demostración', 'f84853a7-eb3f-4b7e-b7c8-b2141e3325ba', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('9e885946-22ca-4f8c-830e-b2a84a80ce2c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'ecbe4879-eca3-4cf4-8f2f-25224eab99bd', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:12.157615+00', NULL, 'Falta plano de distribución', '18e37a30-70de-4a41-b0bb-3d1ba2cdfd33', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f07eb695-e073-4c9a-ac2c-c321145035f6', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'e93a1aa3-9d89-4066-a7b8-201cd9b20a51', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0007", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 107.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Taller Rojas 7", "observaciones": null, "fecha_solicitud": "2026-07-22", "fecha_inspeccion": null}', '2026-10-20 09:42:12.210063+00', NULL, 'Carga de datos de demostración', 'f83c69f1-81e7-436e-a8a8-d1dfe88968b5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('9356ce76-65dd-4c7f-919c-6a60b299cb6a', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'e93a1aa3-9d89-4066-a7b8-201cd9b20a51', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.223582+00', NULL, 'Carga de datos de demostración', '960c66f8-2ab0-4b05-bd94-38625acb01d4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8187eaee-d054-447f-a7bd-8c38f0e0b595', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'e93a1aa3-9d89-4066-a7b8-201cd9b20a51', 'UPDATE', '{"state": "en_revision"}', '{"state": "rechazada"}', '2026-10-20 09:42:12.246819+00', NULL, 'Carga de datos de demostración', 'f624f107-4c6d-431e-83ab-c52e280ee118', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1b7d927b-d045-4406-b4b8-423699b0a481', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f1fab9f1-f034-4ded-be41-42f93b80f817', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0008", "predio": "a84b0be0-62c8-4ff6-b3a6-54857c5218b0", "resultado": null, "area_local": 260.3, "solicitante": "a81f7da9-9ec6-4621-9615-bb97ede93564", "razon_social": "Estudio Castillo 8", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-25", "fecha_inspeccion": null}', '2026-10-20 09:42:12.29972+00', NULL, 'Carga de datos de demostración', 'ae6339d9-4468-4558-8861-c28854aa8013', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('790da7e1-435f-4e56-9f6d-cf7319350351', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f1fab9f1-f034-4ded-be41-42f93b80f817', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.317777+00', NULL, 'Carga de datos de demostración', 'a4e6adaf-7ab6-4f1f-bd97-c390380e1fe1', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5d2073e8-b657-40ba-be16-578c00ef7581', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}', '2026-10-20 09:42:12.390287+00', NULL, 'Carga de datos de demostración', '7477903a-f7db-4037-97f3-b291abb571b1', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8e4b9ec7-32df-41c7-88b8-ef7eb66c98b9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.407191+00', NULL, 'Carga de datos de demostración', 'b574389e-bbf7-43dd-938d-2c6185689b4f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('045fd5c1-85ab-4c09-add0-5a893803aa7b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'f4b4c79c-3b1b-4686-b81d-45f19ec00640', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "carmen.vargas.huaman@correo.example", "activo": true, "codigo": "C-0017", "nombre": "Carmen Vargas Huamán", "telefono": "960644310", "direccion": "Jr. Ica 833, Distrito Demo", "documento": "14217143"}', '2026-10-20 09:42:09.657704+00', NULL, NULL, '509b914c-ba7c-421e-be09-1a19dec526dc', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('0c471816-865c-4c2f-9ea1-8a33797c37a7', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '7b2b6529-6c81-444f-950d-1635e123803d', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "textiles.del.sur.s.a.c@correo.example", "activo": true, "codigo": "C-0018", "nombre": "Textiles del Sur S.A.C.", "telefono": "910430526", "direccion": "Calle Las Gardenias 851, Distrito Demo", "documento": "20612908744"}', '2026-10-20 09:42:09.698643+00', NULL, NULL, 'c73fb098-1553-4697-9b73-a65f76eed35e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('da5ff1ef-7a75-4116-a83c-345bc1bf86af', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '4bf492c9-9874-443c-9f24-74d0643acb92', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "ana.caceres.torres@correo.example", "activo": true, "codigo": "C-0019", "nombre": "Ana Cáceres Torres", "telefono": "970351778", "direccion": "Av. Primavera 958, Distrito Demo", "documento": "34362300"}', '2026-10-20 09:42:09.745867+00', NULL, NULL, '9714210b-7b6f-4027-91a4-004988c46bc4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('397e51ad-2244-4ddc-9e96-f3b0055a33ba', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '68d95c39-57f5-4d1f-8595-153df4f76aa5', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "jorge.chavez.ramos@correo.example", "activo": true, "codigo": "C-0020", "nombre": "Jorge Chávez Ramos", "telefono": "923794305", "direccion": "Calle Las Gardenias 392, Distrito Demo", "documento": "65345762"}', '2026-10-20 09:42:09.790686+00', NULL, NULL, 'e2bbfa66-08ef-42c4-96db-6f9d87122210', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('43da97c6-2e91-4482-99ff-e5be82f09079', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '4a5537fa-da47-45c1-ae1c-c3a96d52c2c1', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "agroindustrias.chancay.s@correo.example", "activo": true, "codigo": "C-0021", "nombre": "Agroindustrias Chancay S.A.C.", "telefono": "948570039", "direccion": "Av. Primavera 337, Distrito Demo", "documento": "20149039149"}', '2026-10-20 09:42:09.828609+00', NULL, NULL, '308eb340-19bc-4b3b-af0a-98031d03928a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('252e29fb-ffe7-47ee-b37e-e9cdb8a57aa1', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'ed8caf7c-8bfe-474f-872b-46b7b39f2454', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "jorge.paredes.mamani@correo.example", "activo": false, "codigo": "C-0022", "nombre": "Jorge Paredes Mamani", "telefono": "973623900", "direccion": "Jr. Las Begonias 394, Distrito Demo", "documento": "14078129"}', '2026-10-20 09:42:09.868009+00', NULL, NULL, 'e0f725ee-dee9-4b26-b17a-4b4628078399', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ac14e55a-f39d-4412-8b4e-f3a167724da3', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'd7cbcddf-68ca-42a4-b3b3-00f5ce99a573', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "lucia.castillo.caceres@correo.example", "activo": true, "codigo": "C-0023", "nombre": "Lucía Castillo Cáceres", "telefono": "975600492", "direccion": "Av. Los Próceres 830, Distrito Demo", "documento": "65782490"}', '2026-10-20 09:42:09.906366+00', NULL, NULL, '18c7d8d2-c3d5-4d61-9d37-8f06025d36de', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1bc91b2b-5be4-4cc8-bd78-2533c8a790dd', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '681cf3b9-50de-4f12-aa8e-4093b43e3db5', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "ferreteria.el.progreso.s@correo.example", "activo": true, "codigo": "C-0024", "nombre": "Ferretería El Progreso S.A.C.", "telefono": "924149560", "direccion": "Av. Primavera 680, Distrito Demo", "documento": "20508010339"}', '2026-10-20 09:42:09.943751+00', NULL, NULL, '5bc01261-8622-4e53-9ae9-397607950da9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('17bc6f9a-4d8c-4f09-8afb-b6ca065e2a5c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '8e6d1243-a7c5-4aac-9b83-88b463337076', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "luis.medina.flores@correo.example", "activo": true, "codigo": "C-0025", "nombre": "Luis Medina Flores", "telefono": "925641834", "direccion": "Av. Primavera 857, Distrito Demo", "documento": "76568545"}', '2026-10-20 09:42:09.980295+00', NULL, NULL, '519ab7c8-a64c-4918-b7d9-40a5725d9700', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c780fb6d-2144-4d1e-9f50-b7281efefa51', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '50ee49d8-325e-4564-ae56-a5de0709a456', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "raul.rojas.paredes@correo.example", "activo": true, "codigo": "C-0026", "nombre": "Raúl Rojas Paredes", "telefono": "923651563", "direccion": "Av. El Sol 376, Distrito Demo", "documento": "48322125"}', '2026-10-20 09:42:10.014373+00', NULL, NULL, 'ddc96190-b349-4946-ac7f-dde570d2296e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('817ebaf9-570f-4e83-bc30-02c40f191027', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'aac7c6c4-5c4f-4228-8a96-4786e71ccf9e', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "botica.santa.rosa.s.a.c@correo.example", "activo": true, "codigo": "C-0027", "nombre": "Botica Santa Rosa S.A.C.", "telefono": "977216433", "direccion": "Av. Los Próceres 436, Distrito Demo", "documento": "20764438533"}', '2026-10-20 09:42:10.074997+00', NULL, NULL, '7ffe1d72-a5d2-4c30-8ebb-b450d967cdd3', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('471c3df8-c323-44a0-986a-74489f1afcfa', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'fce2430d-6b2d-4c23-a75d-45ddf38ff015', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "maria.quispe.salazar@correo.example", "activo": true, "codigo": "C-0028", "nombre": "María Quispe Salazar", "telefono": "949009322", "direccion": "Av. Los Próceres 784, Distrito Demo", "documento": "99179682"}', '2026-10-20 09:42:10.120024+00', NULL, NULL, 'b2ebca88-9e77-4fa2-939d-5b4034a89cdf', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('dbc59d8f-ba19-4288-87f0-a6a970d16311', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', 'a81f7da9-9ec6-4621-9615-bb97ede93564', 'CREATE', NULL, '{"tipo": "PERSONA_NATURAL", "email": "victor.chavez.caceres@correo.example", "activo": true, "codigo": "C-0029", "nombre": "Víctor Chávez Cáceres", "telefono": "954207550", "direccion": "Jr. Huallaga 765, Distrito Demo", "documento": "60959642"}', '2026-10-20 09:42:10.162215+00', NULL, NULL, 'aa745326-6db9-4870-9fb4-fd90bac76d13', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8535a896-3f2b-43ef-bf4d-58e2853ea428', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'contribuyente', '2d8fbbd8-3413-465c-a318-596a8b8c2989', 'CREATE', NULL, '{"tipo": "PERSONA_JURIDICA", "email": "restaurante.la.huerta.s.@correo.example", "activo": true, "codigo": "C-0030", "nombre": "Restaurante La Huerta S.A.C.", "telefono": "943254604", "direccion": "Calle Las Gardenias 395, Distrito Demo", "documento": "20175875079"}', '2026-10-20 09:42:10.204531+00', NULL, NULL, '83570e2b-5d68-482c-b174-a0390e3f3de9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('a0acbe0c-e72d-48e8-97d8-f0c77a557d03', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'a5f07856-8281-4392-9a55-720ac56fe5fe', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 517.43, "codigo": "P-0001", "titular": "846dbc52-4c85-4b69-a752-a035c14add2a", "direccion": "Calle Los Pinos 749, Distrito Demo", "valor_autovaluo": 214470}', '2026-10-20 09:42:10.294766+00', NULL, NULL, '4cca6897-b25e-4da0-b513-e8162b9c7e5f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('620e1c46-8a61-475c-82ed-cbda245c7792', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'ad910484-8b39-4f3f-8195-7882b6076d87', 'CREATE', NULL, '{"uso": "INDUSTRIAL", "area": 746.24, "codigo": "P-0002", "titular": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "direccion": "Jr. Huallaga 439, Distrito Demo", "valor_autovaluo": 832299}', '2026-10-20 09:42:10.353736+00', NULL, NULL, 'a6bd1618-5d00-47f8-ab7a-24f4233cc017', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('e603513a-6ae4-4bb0-9874-fba7eb3610d3', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '8c717ce7-5524-4560-99bd-5b02c47f3fce', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 1354.36, "codigo": "P-0003", "titular": "ed8caf7c-8bfe-474f-872b-46b7b39f2454", "direccion": "Calle Los Pinos 357, Distrito Demo", "valor_autovaluo": 1060325}', '2026-10-20 09:42:10.399858+00', NULL, NULL, '210dcadf-2554-4ae6-ae10-c6beffd90b0e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1c060107-113a-4469-9536-87367dd0b710', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '5f17835c-6d2f-4b5b-b5e5-341ccb3a574f', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 964.09, "codigo": "P-0004", "titular": "a81f7da9-9ec6-4621-9615-bb97ede93564", "direccion": "Jr. Ica 102, Distrito Demo", "valor_autovaluo": 580952}', '2026-10-20 09:42:10.442937+00', NULL, NULL, 'a1a47391-0ef7-463e-9b1d-3318e33c8e85', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c4d50040-7966-4ae2-b4ad-c57a9864ab07', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '2c0c3d6c-c603-4757-8a69-ceaac3546e64', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 507.75, "codigo": "P-0005", "titular": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "direccion": "Jr. Huallaga 605, Distrito Demo", "valor_autovaluo": 535148}', '2026-10-20 09:42:10.481741+00', NULL, NULL, 'c10ce1fb-9680-4b7b-be23-3d230d611e6b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d4d68287-ed0f-49c2-af05-8a0f427ce1ee', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '3a7aa199-1854-4416-97be-4a329cbc3c78', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 1770.61, "codigo": "P-0006", "titular": "f7f597ae-3131-4f67-9a98-693c6dbaf031", "direccion": "Calle Las Gardenias 643, Distrito Demo", "valor_autovaluo": 1328326}', '2026-10-20 09:42:10.548838+00', NULL, NULL, 'b614128d-98ca-4b48-a0b4-744b8a52f7f0', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('69fb89f6-a555-45e8-a995-66692a7e3f8b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'a9f7e251-496c-44f1-9f40-12f253554622', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 1772.56, "codigo": "P-0007", "titular": "68d95c39-57f5-4d1f-8595-153df4f76aa5", "direccion": "Av. El Sol 699, Distrito Demo", "valor_autovaluo": 1137952}', '2026-10-20 09:42:10.591757+00', NULL, NULL, 'fb5f2f8a-a888-4142-817e-937d36bd79a4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f79fbf1c-e033-44be-a5be-f81596922017', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'd1f180c9-1e14-4559-93dd-75697358f83d', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 960.08, "codigo": "P-0008", "titular": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "direccion": "Jr. Las Begonias 298, Distrito Demo", "valor_autovaluo": 361546}', '2026-10-20 09:42:10.648473+00', NULL, NULL, '31b65418-7e15-4b09-826e-f00a0c2c56eb', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d7dbd2b7-2e42-4b7e-913a-b71e911498a0', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'd1548202-a8ae-4a63-9196-4f8894264a7b', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 117.55, "codigo": "P-0009", "titular": "7c9fb903-a304-4e72-8f48-9bd9340a4d82", "direccion": "Jr. Huallaga 967, Distrito Demo", "valor_autovaluo": 43085}', '2026-10-20 09:42:10.702391+00', NULL, NULL, '6f82a59c-4624-40b0-a0c6-3e808cb56bbd', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('883769f4-9d27-450b-b51a-83221dfc9f14', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'f579c281-f200-40e1-96cd-8deaa7f0e553', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 742.57, "codigo": "P-0010", "titular": "2f24c06a-1df3-4791-8330-56897fa5a0d0", "direccion": "Calle Los Pinos 762, Distrito Demo", "valor_autovaluo": 270433}', '2026-10-20 09:42:10.750981+00', NULL, NULL, '4afc6219-3bea-4a9b-982f-c22e7636c3f6', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('632ae034-6058-417d-a3db-18a8a552fe6f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '540f8841-1d8c-47e7-b126-ecbbcddc2b1e', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 1516.68, "codigo": "P-0011", "titular": "7b2b6529-6c81-444f-950d-1635e123803d", "direccion": "Av. Los Próceres 684, Distrito Demo", "valor_autovaluo": 1352004}', '2026-10-20 09:42:10.794216+00', NULL, NULL, 'd8f45fbe-3b89-4e25-9d10-74292cfebcea', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f3e7df99-dc66-48cb-a9de-1ed7676c515a', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '227e7b9c-ed20-40f3-9d97-c38c75f06eb8', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 943.27, "codigo": "P-0012", "titular": "8e6d1243-a7c5-4aac-9b83-88b463337076", "direccion": "Jr. Las Begonias 621, Distrito Demo", "valor_autovaluo": 631574}', '2026-10-20 09:42:10.846451+00', NULL, NULL, 'e5adb1e5-2dbb-4c93-938d-b2ead8b76326', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c8bf3c61-9441-44d8-b5d9-394f036303a2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 535.14, "codigo": "P-0013", "titular": "63b995af-c0ef-4290-8981-eab21020a4e4", "direccion": "Jr. Ica 667, Distrito Demo", "valor_autovaluo": 264870}', '2026-10-20 09:42:10.888618+00', NULL, NULL, '31639ee0-826e-4a9c-b408-1efbb041bc15', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3a16198d-279d-4178-ac2a-17aca5e96093', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '3b211ef7-e67f-4a3d-9a0b-12979deb7cde', 'CREATE', NULL, '{"uso": "INDUSTRIAL", "area": 781.83, "codigo": "P-0014", "titular": "5c59a1f5-97ae-45cf-9ef3-f142f8a41f75", "direccion": "Calle Las Gardenias 530, Distrito Demo", "valor_autovaluo": 778204}', '2026-10-20 09:42:10.934206+00', NULL, NULL, '81acfe9c-ba55-4c51-a595-aa39cc26cf30', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('94ac0d09-9ea9-43a9-b3ea-78765104fa6f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'e74d2236-a0bc-4942-8523-73dd76995381', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 1972.1, "codigo": "P-0015", "titular": "170f415c-27ce-4b59-9acb-027585d7f293", "direccion": "Av. Los Próceres 735, Distrito Demo", "valor_autovaluo": 1491161}', '2026-10-20 09:42:10.982585+00', NULL, NULL, '46616db0-8453-4332-9a40-d2eee5d2ef9e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d33df301-641a-4fab-aa0a-5ca9b4e08a19', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 292.98, "codigo": "P-0016", "titular": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "direccion": "Av. El Sol 726, Distrito Demo", "valor_autovaluo": 232678}', '2026-10-20 09:42:11.029523+00', NULL, NULL, 'bc84dc72-5723-452f-b22a-4e7f2f544d4b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('38a952a5-6d81-4e72-a0c3-121383591bef', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '4a6042dc-228f-4671-b0e3-7f340b43e685', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 1290.66, "codigo": "P-0017", "titular": "2d8fbbd8-3413-465c-a318-596a8b8c2989", "direccion": "Calle Las Gardenias 853, Distrito Demo", "valor_autovaluo": 1033290}', '2026-10-20 09:42:11.074816+00', NULL, NULL, 'bad92adf-0e32-4fb7-85dd-dbe804806fc2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('dd90442d-f7b5-4566-a053-61f98b2ca0c8', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '4e105a70-c0fc-46c9-8d80-51532fa3ac8c', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 332.08, "codigo": "P-0018", "titular": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "direccion": "Jr. Huallaga 718, Distrito Demo", "valor_autovaluo": 118655}', '2026-10-20 09:42:11.117795+00', NULL, NULL, 'c33d63b2-7f64-4525-8c24-d645c8f40781', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1bb8c06f-82e6-4ec7-a4c7-ffec58b44eb2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '766b8f85-b5e6-4870-abe2-e6677e1e9bc5', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 534.78, "codigo": "P-0019", "titular": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "direccion": "Calle Las Gardenias 401, Distrito Demo", "valor_autovaluo": 459436}', '2026-10-20 09:42:11.160267+00', NULL, NULL, '6eaad59d-85d9-481b-91b0-a7d41693a7cf', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c461048e-9b4c-4f72-a09d-bf665b63cf16', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'ba9b3af8-e337-4d4d-8de6-e1b8f926b06d', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 443.38, "codigo": "P-0020", "titular": "4a5537fa-da47-45c1-ae1c-c3a96d52c2c1", "direccion": "Calle Los Pinos 398, Distrito Demo", "valor_autovaluo": 181879}', '2026-10-20 09:42:11.201872+00', NULL, NULL, '973706da-9262-4dc8-be58-7281b9933452', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('298d8c7d-b070-4169-9d9d-3ff3120a061c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'a26ddbbf-828d-4d0e-aa38-c46298053f2d', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 1930.83, "codigo": "P-0021", "titular": "fce2430d-6b2d-4c23-a75d-45ddf38ff015", "direccion": "Av. El Sol 604, Distrito Demo", "valor_autovaluo": 821033}', '2026-10-20 09:42:11.241174+00', NULL, NULL, 'cbe35608-592e-4f0f-8245-3d8159bb59e8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6edcd41b-df37-4486-94f4-98a8b78eb617', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'fc88650a-e9dc-4122-9530-9466e16c5394', 'CREATE', NULL, '{"uso": "RESIDENCIAL", "area": 1279.64, "codigo": "P-0022", "titular": "5735071c-f421-4bf8-801a-ab912ab8186f", "direccion": "Jr. Huallaga 522, Distrito Demo", "valor_autovaluo": 1141856}', '2026-10-20 09:42:11.27943+00', NULL, NULL, '1382be4d-6df6-496f-b9a6-bedb1cbd535c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('40498699-bbdc-45b7-8d94-b16626354ebb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '73cb1aff-421a-4366-a9da-e990f6e815ee', 'CREATE', NULL, '{"uso": "INDUSTRIAL", "area": 1178.95, "codigo": "P-0023", "titular": "16b55360-6e5a-4721-acb4-7ea60bec232d", "direccion": "Jr. Huallaga 590, Distrito Demo", "valor_autovaluo": 1135897}', '2026-10-20 09:42:11.324761+00', NULL, NULL, '5e99281f-d355-4bdb-87e6-6d8489996436', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('af1c88ed-6cdf-4424-b721-913474863e3b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', '46222881-cddc-4be0-874c-331c2c07cdb0', 'CREATE', NULL, '{"uso": "COMERCIAL", "area": 597.14, "codigo": "P-0024", "titular": "4bf492c9-9874-443c-9f24-74d0643acb92", "direccion": "Av. Los Próceres 650, Distrito Demo", "valor_autovaluo": 270557}', '2026-10-20 09:42:11.373973+00', NULL, NULL, '5c9590d3-a0a6-4d7e-a6ce-8988c0ced098', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('7da2fd6e-dcab-4f30-a061-73beb5809dda', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'predio', 'a84b0be0-62c8-4ff6-b3a6-54857c5218b0', 'CREATE', NULL, '{"uso": "INDUSTRIAL", "area": 501.33, "codigo": "P-0025", "titular": "50ee49d8-325e-4564-ae56-a5de0709a456", "direccion": "Calle Las Gardenias 406, Distrito Demo", "valor_autovaluo": 361940}', '2026-10-20 09:42:11.424536+00', NULL, NULL, 'c9643d32-c99b-407b-9cdf-333021297670', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6afa555b-7f9a-4ff8-a285-7b1a3c2c3c12', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:12.434306+00', NULL, 'Falta plano de distribución', 'c75e3878-8063-424e-a26b-3178df4ba026', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('da7fda6a-442c-40f8-8b23-1f2d123e0f99', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '3696eeb1-3a66-4717-a255-0bdd6feac753', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0001", "predio": "5f17835c-6d2f-4b5b-b5e5-341ccb3a574f", "resultado": null, "area_local": 228.3, "solicitante": "16b55360-6e5a-4721-acb4-7ea60bec232d", "razon_social": "Bodega Mamani 1", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}', '2026-10-20 09:42:11.489595+00', NULL, 'Carga de datos de demostración', '81f6139c-67b5-4db0-8f51-bdd80813123e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('a4a6ddc8-c1a6-4434-884d-fec3712bfc2f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '3696eeb1-3a66-4717-a255-0bdd6feac753', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.515895+00', NULL, 'Carga de datos de demostración', '1348cd6c-d146-4b63-ada3-fec411c9dd4f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('daaa8b72-d3e2-4e41-8f40-d9aec5162fac', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '2026-10-20 09:42:11.627483+00', NULL, 'Carga de datos de demostración', '6c3930be-4bbd-47bc-a5bf-a27d1b880a45', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('bec7ad1d-11bf-4096-93b2-9c524a19d36e', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:11.64487+00', NULL, 'Carga de datos de demostración', '8c975bfd-1ab4-4455-a4a0-2c3e592846bd', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('0f95e87f-6a74-40ab-bc44-5434b725e34b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:11.683086+00', NULL, 'Falta plano de distribución', 'cbb4f6d2-a041-4653-b371-8e8a61338868', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8d8d8d7b-0edf-4ede-8cca-9e39df4fd14c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:12.721108+00', NULL, 'Carga de datos de demostración', '90365f93-b2c5-4f36-86f4-b0d532bedf83', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('61be8afc-da5a-4b35-b305-0ada4e9225eb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '71566a41-ef9f-44e3-bf19-42df253384de', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0012", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 105.2, "solicitante": "f7f597ae-3131-4f67-9a98-693c6dbaf031", "razon_social": "Botica Paredes 12", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-10", "fecha_inspeccion": null}', '2026-10-20 09:42:12.791949+00', NULL, 'Carga de datos de demostración', '0420b2fc-7b6d-4068-85c4-d7d84cd74022', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('042cf5dc-5785-4247-bea0-f710db6dedcd', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f65ac4de-c341-49f3-88ba-cf3a2265a374', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0013", "predio": "e74d2236-a0bc-4942-8523-73dd76995381", "resultado": null, "area_local": 156.1, "solicitante": "681cf3b9-50de-4f12-aa8e-4093b43e3db5", "razon_social": "Botica Vargas 13", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}', '2026-10-20 09:42:12.847782+00', NULL, 'Carga de datos de demostración', '625b48b0-0d05-441e-9413-00f05332db1d', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('89e64036-2ec6-4f6b-8549-16d5fee36315', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f65ac4de-c341-49f3-88ba-cf3a2265a374', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.861613+00', NULL, 'Carga de datos de demostración', '3df77f8f-3b45-4853-a14f-fd2a383e20d2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3e10f579-8b23-4fdf-b2b2-cba36d5b1f9d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'f65ac4de-c341-49f3-88ba-cf3a2265a374', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:12.890423+00', NULL, 'Falta plano de distribución', '3b2613b1-8a6d-49bf-88f5-72877fee99c9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d2f0ccaf-dfb9-45e5-97bf-ac0a0c5c6ee6', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9263f1a8-12a3-46aa-8f04-a1505239fa45', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0014", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 221.7, "solicitante": "5735071c-f421-4bf8-801a-ab912ab8186f", "razon_social": "Taller Castillo 14", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}', '2026-10-20 09:42:12.966357+00', NULL, 'Carga de datos de demostración', '2287c369-40db-471c-8ffd-54aa1b9e1624', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d4e1d8fe-3898-4864-8c47-e5aedaccbb0f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9263f1a8-12a3-46aa-8f04-a1505239fa45', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.984167+00', NULL, 'Carga de datos de demostración', '80c60c57-5dd9-4793-b853-e6ba88e82f05', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('359cdb00-acb0-4822-810d-f2ccb289bf09', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9263f1a8-12a3-46aa-8f04-a1505239fa45', 'UPDATE', '{"state": "en_revision"}', '{"state": "rechazada"}', '2026-10-20 09:42:13.032798+00', NULL, 'Carga de datos de demostración', 'ebab79ea-dffe-4f58-863d-c24bd18abd03', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3645f423-39ba-43b8-89a7-9f8d2c1e3416', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'ISSUE', NULL, NULL, '2026-10-20 09:42:13.080477+00', '7e110b83-d3f6-4e4b-85c9-3cb41f610aa8', NULL, '2497fe52-8454-4ea4-bb77-c5c8631d8406', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('209a241f-3a02-42a9-8cc2-ee7be9244cbd', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'UPDATE', '{"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}', '{"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": "Aprobada el 2026-10-20", "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}', '2026-10-20 09:42:13.131785+00', NULL, 'automation ''certificar-aprobada''', '2497fe52-8454-4ea4-bb77-c5c8631d8406', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('803fc7f7-3325-451b-ab0f-32abd96304ab', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}', '2026-10-20 09:42:13.182013+00', NULL, 'Carga de datos de demostración', '7435a502-520c-42b3-ae22-7ba808929b82', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5b154533-6e73-48a7-a65f-1aee8d427d7d', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'ISSUE', NULL, NULL, '2026-10-20 09:42:13.211874+00', 'dcd6f108-b32b-4465-b48b-771071b929bd', NULL, '681186e5-d773-421f-8ee1-a37b2a056651', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('56c69b5f-7bc2-4986-ad6b-188079041283', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'UPDATE', '{"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '{"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": "Aprobada el 2026-10-20", "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '2026-10-20 09:42:13.257175+00', NULL, 'automation ''certificar-aprobada''', '681186e5-d773-421f-8ee1-a37b2a056651', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8d9fc1f8-abd9-4f1f-bd02-8967bd946496', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.278084+00', NULL, 'Carga de datos de demostración', '3e7c119d-78ce-4797-84fc-a808f62d11a0', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('55a684ff-563f-4ecf-b042-d7cc627cdb2e', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:13.308409+00', NULL, 'Carga de datos de demostración', '49916294-1147-49a9-bf07-f72a478a313c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('42c0fb93-2884-4957-aedc-d747f5777cbb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.459255+00', NULL, 'Carga de datos de demostración', '9f5dd56a-f8ed-4051-a3f6-13f4fd26bf48', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('06e7ab57-cf1f-4155-972e-e06ea02d5665', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:12.490604+00', NULL, 'Carga de datos de demostración', '2497fe52-8454-4ea4-bb77-c5c8631d8406', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('acc7d7a6-b892-4d9f-afd2-28e4655d7e51', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '24e2a46b-913c-4e70-b075-17229a78f771', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0010", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 281.1, "solicitante": "4a5537fa-da47-45c1-ae1c-c3a96d52c2c1", "razon_social": "Taller Flores 10", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}', '2026-10-20 09:42:12.539513+00', NULL, 'Carga de datos de demostración', 'd8a142a4-837f-44d7-91f3-775876f9fc50', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('69c19420-1061-4f2d-8f0a-0eb8f0db8d6d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '24e2a46b-913c-4e70-b075-17229a78f771', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.555812+00', NULL, 'Carga de datos de demostración', '5b9adcbb-0dc8-49a2-a2ff-482012b73408', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b2a02f5f-0ea0-4d7c-bae7-37b87f8a4e7c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '2026-10-20 09:42:12.6121+00', NULL, 'Carga de datos de demostración', '80845fe4-f8d5-4ad5-9eee-29f8c6e3faf2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b6858ab1-b1aa-431b-aaaa-355d49212235', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.625151+00', NULL, 'Carga de datos de demostración', '93d9da8c-b3d4-40eb-b761-0afa1070b4b1', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('4aa48af4-d362-4b93-ae8b-ee82a4dc75fa', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:12.654547+00', NULL, 'Falta plano de distribución', 'b6ae131b-9555-455f-ac69-bbe5fc5ecfe5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('37f53eba-09dc-40a8-8e4b-db98a9a113a5', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:12.681763+00', NULL, 'Carga de datos de demostración', 'cb5bd1e6-7796-495c-b173-79ed9ead73d4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('a2350f26-eba9-4ea0-9510-ab19b8b58979', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'ISSUE', NULL, NULL, '2026-10-20 09:42:12.878634+00', '1b0d08d6-107f-481f-b651-fc5e2688bf76', NULL, 'bf016f13-687b-4a5c-80bd-d8c30b243e0a', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('50af89bb-f2aa-4dea-80db-15f54b893e90', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'UPDATE', '{"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}', '{"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": "Aprobada el 2026-10-20", "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}', '2026-10-20 09:42:12.99641+00', NULL, 'automation ''certificar-aprobada''', 'bf016f13-687b-4a5c-80bd-d8c30b243e0a', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3d0a0f34-6095-4fcc-9b88-365f96a82ebe', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '98778346-7f16-4252-be16-6781cfd7b6a2', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0015", "predio": "a26ddbbf-828d-4d0e-aa38-c46298053f2d", "resultado": null, "area_local": 208.2, "solicitante": "170f415c-27ce-4b59-9acb-027585d7f293", "razon_social": "Restaurante Torres 15", "observaciones": null, "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}', '2026-10-20 09:42:13.094815+00', NULL, 'Carga de datos de demostración', 'e6bd091b-ddd3-48de-95c3-a2f50b5b7fa2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('87ee7866-e64c-40ab-9e78-67ef1e205b00', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '98778346-7f16-4252-be16-6781cfd7b6a2', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.119757+00', NULL, 'Carga de datos de demostración', 'f4b52fef-5365-411d-bbdb-6eb799462f45', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8070c82f-bc91-489d-89b1-14b7d7a57fa9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.21644+00', NULL, 'Carga de datos de demostración', '5bb107ae-a360-4ee3-a075-00899e3107b8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('89096757-7547-4dec-bfea-187dc6fabb1c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:13.247705+00', NULL, 'Falta plano de distribución', '915166fb-a593-4ce5-81ce-8bbc1a1f5272', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('cf7fe6fc-b6a4-4360-b3fa-8cecc7cc2e70', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '1cfb238e-0268-4a9b-91e4-cf84758797f1', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0017", "predio": "ad910484-8b39-4f3f-8195-7882b6076d87", "resultado": null, "area_local": 245.3, "solicitante": "846dbc52-4c85-4b69-a752-a035c14add2a", "razon_social": "Taller Ramos 17", "observaciones": null, "fecha_solicitud": "2026-08-25", "fecha_inspeccion": null}', '2026-10-20 09:42:13.368264+00', NULL, 'Carga de datos de demostración', '6a71e656-af1e-498a-b5ee-f5b854ee4258', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('bc6dd5df-62b3-4dd0-8e87-61ba1dd36855', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '1cfb238e-0268-4a9b-91e4-cf84758797f1', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.384321+00', NULL, 'Carga de datos de demostración', '4cbb5c1a-1c57-4c51-a46b-9159ab52d0ad', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('81259e1d-4c26-449f-8aac-6141f6f1c93b', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}', '2026-10-20 09:42:13.437476+00', NULL, 'Carga de datos de demostración', '28462fad-25a2-4ee9-af04-e2fe00aa5514', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('03ac32a6-0947-44a4-ab37-917f89f50782', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.449331+00', NULL, 'Carga de datos de demostración', 'b99f8eca-4105-40c4-a3a2-2c16103b848a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c611afea-11e1-4098-b038-b13cff63e620', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:13.479725+00', NULL, 'Falta plano de distribución', '9d585221-ff35-47e8-a7c4-5718ee9c76f6', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('70fe16fe-1b4a-4fee-9cc2-c0a3333c62ec', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.499307+00', NULL, 'Carga de datos de demostración', '3a0ed993-78c7-4619-ad3e-4c3b627a0485', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('fe31b1bd-c411-4690-a4a6-d6b9aec7b488', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:13.524282+00', NULL, 'Carga de datos de demostración', 'f9bce694-1c4e-4877-96a4-8dfebe5f64a7', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('036e0691-5719-4981-9826-cf2a099a9dce', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'a9ea42e6-aaa9-4217-8091-ba5fc84fd32b', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0019", "predio": "d1f180c9-1e14-4559-93dd-75697358f83d", "resultado": null, "area_local": 208.7, "solicitante": "2d8fbbd8-3413-465c-a318-596a8b8c2989", "razon_social": "Restaurante Torres 19", "observaciones": null, "fecha_solicitud": "2026-08-04", "fecha_inspeccion": null}', '2026-10-20 09:42:13.579991+00', NULL, 'Carga de datos de demostración', '09643a21-e46d-421a-ba71-e79502d8cf78', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('df4a8142-2844-40b6-a02d-4fed19504c9d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '5af20f44-aa18-476c-bf07-1b844de0b892', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0020", "predio": "540f8841-1d8c-47e7-b126-ecbbcddc2b1e", "resultado": null, "area_local": 158.5, "solicitante": "2f24c06a-1df3-4791-8330-56897fa5a0d0", "razon_social": "Estudio Medina 20", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-07", "fecha_inspeccion": null}', '2026-10-20 09:42:13.616018+00', NULL, 'Carga de datos de demostración', '7b39874a-d4d1-4a92-8b63-3a2f424f57f8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('4d278bff-3e28-4757-8274-3e38252749b2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '5af20f44-aa18-476c-bf07-1b844de0b892', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.630513+00', NULL, 'Carga de datos de demostración', '723d25d6-bdea-434e-84ca-cd7dded68230', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6a424dd2-f128-45d7-a8c9-ad827f6d8305', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '52b50a86-29eb-4ac6-8947-776ddd57bb8b', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0021", "predio": "3b211ef7-e67f-4a3d-9a0b-12979deb7cde", "resultado": null, "area_local": 21.9, "solicitante": "ed8caf7c-8bfe-474f-872b-46b7b39f2454", "razon_social": "Taller Chávez 21", "observaciones": null, "fecha_solicitud": "2026-08-10", "fecha_inspeccion": null}', '2026-10-20 09:42:13.728682+00', NULL, 'Carga de datos de demostración', 'a03cbddd-c8d4-4ac4-a161-dbd86990b288', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('de99f42e-65f9-45c0-a55d-3646f4e9314d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '52b50a86-29eb-4ac6-8947-776ddd57bb8b', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.74494+00', NULL, 'Carga de datos de demostración', 'b0fcf7f8-05a2-4329-a445-6c093637e436', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ac378102-9d47-4ab9-b507-bf1c55c9fbb1', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'UPDATE', '{"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '{"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": "Aprobada el 2026-10-20", "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}', '2026-10-20 09:42:13.790972+00', NULL, 'automation ''certificar-aprobada''', '90365f93-b2c5-4f36-86f4-b0d532bedf83', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('be52982e-d88b-424a-8874-2ba709e859b9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9b70de67-dde6-4eff-8940-f5665ebf8529', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0022", "predio": "4a6042dc-228f-4671-b0e3-7f340b43e685", "resultado": null, "area_local": 105.8, "solicitante": "26e36d67-2d3a-48c0-8569-aa97fa272bad", "razon_social": "Restaurante Ramos 22", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}', '2026-10-20 09:42:13.841409+00', NULL, 'Carga de datos de demostración', 'c311d428-002e-4054-ae0c-713823942dee', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3f8e54a0-f5e4-4b89-ae32-f2bc2aa7d394', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9b70de67-dde6-4eff-8940-f5665ebf8529', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.863213+00', NULL, 'Carga de datos de demostración', '2af034c5-14ac-459f-8ab2-ac7c95b0adcc', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('cc0c69be-6adf-4c64-8957-34c1e5b62e28', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}', '2026-10-20 09:42:13.91615+00', NULL, 'Carga de datos de demostración', '20cb55f6-01d5-49ea-907f-a9103d818126', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('83d6652a-85a4-43af-a63d-a7be177e65a9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.941074+00', NULL, 'Carga de datos de demostración', 'a234da9d-3a9c-42ac-b3c2-a0d1b46c3d92', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('39c0a608-315f-4b29-9fde-748cb871cd09', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:13.967113+00', NULL, 'Falta plano de distribución', 'a3864342-1ecd-46c4-ba53-061145b957a2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f26636be-782a-4966-95c7-c46b2f064046', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:13.98865+00', NULL, 'Carga de datos de demostración', '30d148d7-f6aa-4881-a28d-7e5b7b9b46e8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('343de0fc-be7d-4f62-9e6b-bd8c3056fab8', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'UPDATE', '{"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}', '{"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": "Aprobada el 2026-10-20", "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}', '2026-10-20 09:42:14.009037+00', NULL, 'automation ''certificar-aprobada''', '49916294-1147-49a9-bf07-f72a478a313c', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('7d1f2560-d0d4-4343-84cb-797372e2e5cd', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:14.012018+00', NULL, 'Carga de datos de demostración', 'a7a355ac-9c93-4c0a-b597-abacabac64e9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c3407e95-b422-4663-a0ce-c9ea80b7d162', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'ISSUE', NULL, NULL, '2026-10-20 09:42:14.041628+00', 'f7f6acec-f33f-43fa-adae-057cce566bb5', NULL, 'f9bce694-1c4e-4877-96a4-8dfebe5f64a7', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6307721b-a5f0-4a48-87f5-4a8053a8fdf9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '1044e268-2c76-4923-8bab-cbeccad576c6', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0024", "predio": "73cb1aff-421a-4366-a9da-e990f6e815ee", "resultado": null, "area_local": 76.6, "solicitante": "8e6d1243-a7c5-4aac-9b83-88b463337076", "razon_social": "Restaurante Rojas 24", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}', '2026-10-20 09:42:14.068446+00', NULL, 'Carga de datos de demostración', '5d295653-d665-4047-a620-71a11aa2c8e0', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5e418311-a304-40e8-905b-3c5fb37f228a', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:14.681033+00', NULL, 'Carga de datos de demostración', '976cdb25-b8f5-4804-bb79-766ce95adf62', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('42e3599a-e756-4a41-b055-ea23330e5e4d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '9c31cc19-7b21-4484-aa64-ed14a2304516', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0031", "predio": "766b8f85-b5e6-4870-abe2-e6677e1e9bc5", "resultado": null, "area_local": 228.7, "solicitante": "16b55360-6e5a-4721-acb4-7ea60bec232d", "razon_social": "Ferretería Flores 31", "observaciones": null, "fecha_solicitud": "2026-09-13", "fecha_inspeccion": null}', '2026-10-20 09:42:14.726117+00', NULL, 'Carga de datos de demostración', 'bce6fd71-a5b4-48dc-9a8b-e4edfe760235', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('786044dd-7861-479c-90c0-37cef8b4375c', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '82137858-4cd9-4f94-9a71-4f49f281ffb0', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0040", "predio": "a26ddbbf-828d-4d0e-aa38-c46298053f2d", "resultado": null, "area_local": 229.2, "solicitante": "4a5537fa-da47-45c1-ae1c-c3a96d52c2c1", "razon_social": "Taller Vargas 40", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-10-13", "fecha_inspeccion": null}', '2026-10-20 09:42:15.590133+00', NULL, 'Carga de datos de demostración', '8ffda88a-8cf2-4dc7-9e29-040ec4c14120', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('49804e36-b0d3-485f-86cc-1c105718c62d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '5af20f44-aa18-476c-bf07-1b844de0b892', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:13.6574+00', NULL, 'Falta plano de distribución', '76208eda-dd7f-41ec-98d7-7961b55e24ad', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('fcae59be-612e-4a4a-80c5-8abf17cf8e02', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'ISSUE', NULL, NULL, '2026-10-20 09:42:13.742391+00', 'a90e3d81-3e7a-4262-a44e-66c818ceae18', NULL, '90365f93-b2c5-4f36-86f4-b0d532bedf83', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('defb14a0-b180-41e9-ab5f-eb427041188d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '52b50a86-29eb-4ac6-8947-776ddd57bb8b', 'UPDATE', '{"state": "en_revision"}', '{"state": "rechazada"}', '2026-10-20 09:42:13.78104+00', NULL, 'Carga de datos de demostración', '2ea3bee6-57dd-4951-a0bc-1d36510aa83c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('480f7e8f-0aba-4716-ac69-1aa9f2db4459', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'ISSUE', NULL, NULL, '2026-10-20 09:42:13.97764+00', '8905e113-fa86-468c-837c-bb1da101f10c', NULL, '49916294-1147-49a9-bf07-f72a478a313c', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1870ee3f-b236-4af0-8ac2-c530f56795ba', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'UPDATE', '{"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}', '{"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": "Aprobada el 2026-10-20", "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}', '2026-10-20 09:42:14.074454+00', NULL, 'automation ''certificar-aprobada''', 'f9bce694-1c4e-4877-96a4-8dfebe5f64a7', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d55e1f78-f428-48e3-bc97-7eb257b5bef2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '1044e268-2c76-4923-8bab-cbeccad576c6', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.08506+00', NULL, 'Carga de datos de demostración', '7a4c159e-0f6a-41e4-8aad-c4fd6353ad42', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('db07f067-d81d-4b18-bd30-2f4ca932c645', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}', '2026-10-20 09:42:14.134283+00', NULL, 'Carga de datos de demostración', '73c98a96-0df9-49ab-bd31-c6a27ccda3d8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ffa314a6-52f1-411a-86b3-5a941bbe1adb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.164307+00', NULL, 'Carga de datos de demostración', '8c868740-f13c-4a1f-9f67-de925c0017b9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b8f0cb76-e856-44e4-8d6d-33caa73f94a5', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:14.192458+00', NULL, 'Falta plano de distribución', '11ac0588-7ae0-45a4-8c97-77e1c1941dfb', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ea018ca7-d6ca-450f-9576-1901ffebc7c2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.213455+00', NULL, 'Carga de datos de demostración', '3072612c-73c4-4739-ba28-f6d51a17e17e', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f222a33a-a8f3-42f9-807d-9831b6aec1b9', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:14.236782+00', NULL, 'Carga de datos de demostración', 'ef275064-14de-4004-88e7-38b559523fe5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b30289dd-a220-457f-9258-72df3b45e9b2', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0b4c15d7-e07d-4c97-b616-47920123d650', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0026", "predio": "5f17835c-6d2f-4b5b-b5e5-341ccb3a574f", "resultado": null, "area_local": 107.5, "solicitante": "f4b4c79c-3b1b-4686-b81d-45f19ec00640", "razon_social": "Restaurante Quispe 26", "observaciones": null, "fecha_solicitud": "2026-09-25", "fecha_inspeccion": null}', '2026-10-20 09:42:14.287627+00', NULL, 'Carga de datos de demostración', '3d90d86e-eda0-4937-aa7a-e940f9fb1ee5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8461ada1-3da2-4e36-a659-b801cdf5a4c7', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '54954b2b-23a1-421e-a341-21618051b2a9', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0027", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 252.6, "solicitante": "fce2430d-6b2d-4c23-a75d-45ddf38ff015", "razon_social": "Botica Chávez 27", "observaciones": null, "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}', '2026-10-20 09:42:14.325606+00', NULL, 'Carga de datos de demostración', 'c4ee7b22-e8e1-445d-8159-0329342729f4', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('8a2d7e3a-9cfb-464b-a6fe-ef0f16698dcf', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '54954b2b-23a1-421e-a341-21618051b2a9', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.342463+00', NULL, 'Carga de datos de demostración', 'd8a6782b-4351-488c-8a7f-30898c9f41ce', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('d86ff495-7d91-4eee-b21d-1bb75ac0087f', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '54954b2b-23a1-421e-a341-21618051b2a9', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:14.366368+00', NULL, 'Falta plano de distribución', 'd88adbc0-b244-4a11-8ae2-9a2b1ba945f0', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('34209fd4-2c5f-448d-ae0d-10628e089c2d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'eba4a091-eca0-43b9-b20a-71e7379c9157', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0028", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 27.1, "solicitante": "5c59a1f5-97ae-45cf-9ef3-f142f8a41f75", "razon_social": "Estudio Paredes 28", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-04", "fecha_inspeccion": null}', '2026-10-20 09:42:14.417953+00', NULL, 'Carga de datos de demostración', '23ef408c-18af-4104-9a8c-ca2caf28c969', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('aca6e383-d827-4df1-9129-84624d7f2ab7', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'eba4a091-eca0-43b9-b20a-71e7379c9157', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.43484+00', NULL, 'Carga de datos de demostración', 'c17f857a-3522-4e7c-b207-ba0f5ff12491', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('22afe368-7a04-4749-8605-195fcab5a1dd', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'eba4a091-eca0-43b9-b20a-71e7379c9157', 'UPDATE', '{"state": "en_revision"}', '{"state": "rechazada"}', '2026-10-20 09:42:14.471217+00', NULL, 'Carga de datos de demostración', '951faa26-e267-4cc8-8d92-6ebd1fd28f2c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('733edca8-97d2-40b2-b28d-4cf0d4a896ec', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'a0da9137-f268-4f34-965e-2fa6fe299f64', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0029", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 210.7, "solicitante": "68d95c39-57f5-4d1f-8595-153df4f76aa5", "razon_social": "Restaurante Cáceres 29", "observaciones": null, "fecha_solicitud": "2026-09-07", "fecha_inspeccion": null}', '2026-10-20 09:42:14.537137+00', NULL, 'Carga de datos de demostración', 'da77397b-674a-432a-a4ae-f4581b4fd90f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('aa6702a9-2cf7-419f-9a90-74855f484885', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'a0da9137-f268-4f34-965e-2fa6fe299f64', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.55199+00', NULL, 'Carga de datos de demostración', '2f5bef5d-726c-4aea-b149-de7177ee9fae', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('a7bf81ae-66ad-4f0f-bc39-dec981f1f828', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}', '2026-10-20 09:42:14.602761+00', NULL, 'Carga de datos de demostración', '12299359-2965-4cf7-89e8-75923e3eecf9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('27a518a2-7995-46df-a2b4-99a4b14e2ea6', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.616529+00', NULL, 'Carga de datos de demostración', '552b5adc-a36e-4cc3-86a8-f8be2a685f6f', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('cddb1f8e-cb28-48e5-ab7b-f8412c74838d', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:14.641627+00', NULL, 'Falta plano de distribución', 'ed4e5343-4be3-489f-aa69-1164593ad78c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('734bef3c-35e8-4552-a6d0-ebd578c66bd3', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.660237+00', NULL, 'Carga de datos de demostración', '9a9f6c45-c153-4dbf-81fc-7c17befea3a9', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('560ce605-883b-4452-800e-4ce10840a9f3', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'ISSUE', NULL, NULL, '2026-10-20 09:42:14.715224+00', '1126d591-377c-4d6a-a5a4-48db6ff65c07', NULL, 'ef275064-14de-4004-88e7-38b559523fe5', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('751aeba9-f8ff-4f9b-86af-cfb76c533574', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'ISSUE', NULL, NULL, '2026-10-20 09:42:14.767944+00', 'd74b9138-479f-432d-b7c8-bffe5ca85614', NULL, 'a7a355ac-9c93-4c0a-b597-abacabac64e9', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6b0ff382-15e7-492d-9cf2-c5180456e53c', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'UPDATE', '{"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}', '{"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": "Aprobada el 2026-10-20", "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}', '2026-10-20 09:42:14.801768+00', NULL, 'automation ''certificar-aprobada''', 'a7a355ac-9c93-4c0a-b597-abacabac64e9', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1046463b-3aad-4f19-a8f4-45ab3db0ab87', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:14.838432+00', NULL, 'Carga de datos de demostración', '90f5a466-9589-46c1-ab7d-737b2a24e881', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('da2c582d-f995-46b3-8b2a-1f826f982852', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'df7c462b-84f2-4283-899f-78e434e54c87', 'CREATE', NULL, '{"giro": "OFICINA", "codigo": "LF-2026-0034", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 288.8, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Ferretería Vargas 34", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}', '2026-10-20 09:42:14.911344+00', NULL, 'Carga de datos de demostración', '1097c296-db6f-4654-a6e8-50ddc97a45a2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('662ec82d-422e-40ec-a23a-439fb8fba474', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'df7c462b-84f2-4283-899f-78e434e54c87', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.928245+00', NULL, 'Carga de datos de demostración', '067a8351-4e3e-4671-8139-21cf52dfcd76', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('387393cf-e511-462d-a298-954b9b3ea299', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'df7c462b-84f2-4283-899f-78e434e54c87', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:14.951945+00', NULL, 'Falta plano de distribución', 'b4714bfa-c771-4a1d-9b69-720ad0d8fcd2', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c79f5490-3b45-40e3-b770-e44b5310b8fb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '3adf714a-ee09-4472-be0a-23ce2ce93250', 'CREATE', NULL, '{"giro": "TALLER", "codigo": "LF-2026-0035", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 119.8, "solicitante": "50ee49d8-325e-4564-ae56-a5de0709a456", "razon_social": "Taller Salazar 35", "observaciones": null, "fecha_solicitud": "2026-09-25", "fecha_inspeccion": null}', '2026-10-20 09:42:14.996186+00', NULL, 'Carga de datos de demostración', '77de9f9c-c699-4909-b967-a6fea5a0775d', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('c8c2544c-6224-4dbd-8d81-c5b3b1adb373', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '3adf714a-ee09-4472-be0a-23ce2ce93250', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:15.007954+00', NULL, 'Carga de datos de demostración', '4a9598e2-2179-41f8-a563-fa11b1e74fe5', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1232f32e-2465-430f-b34f-912981735712', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '3adf714a-ee09-4472-be0a-23ce2ce93250', 'UPDATE', '{"state": "en_revision"}', '{"state": "rechazada"}', '2026-10-20 09:42:15.034912+00', NULL, 'Carga de datos de demostración', 'cb78ca76-dbf0-4c24-a681-90548d5d3a5a', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('0a8073fe-73f6-46de-b9ed-7d997ae4d5a1', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '08afe0b4-153d-42fd-9aa7-ee1d572e71f4', 'CREATE', NULL, '{"giro": "BODEGA", "codigo": "LF-2026-0036", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 193.8, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Estudio Vargas 36", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}', '2026-10-20 09:42:15.079633+00', NULL, 'Carga de datos de demostración', '9e975e89-dcd1-4917-9caa-98e3966e2138', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('f3bffa9d-9490-450d-a3b1-75603e2fbe19', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '08afe0b4-153d-42fd-9aa7-ee1d572e71f4', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:15.092473+00', NULL, 'Carga de datos de demostración', 'b3b06497-c4d3-494d-ad1c-90401d65112d', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('e13ff8c3-820e-4c38-8041-ba4f0a7c5f14', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'CREATE', NULL, '{"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}', '2026-10-20 09:42:15.138461+00', NULL, 'Carga de datos de demostración', '32c68ac3-ddb3-41ce-8f7c-0899ba76dd1c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('3e6683e6-6794-45d2-89b0-8dcb51d29c45', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:15.152912+00', NULL, 'Carga de datos de demostración', '469d43a7-1a8b-4276-b945-db5702c4f866', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('943e222e-a76b-4a53-bd84-640b9dfb9d79', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:15.174283+00', NULL, 'Falta plano de distribución', 'b68f8f85-ceb0-458a-b066-f02da0066ebe', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('660e13f5-8332-4c6a-bc0d-66ac3de6e6ca', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:15.193851+00', NULL, 'Carga de datos de demostración', '83725348-4f48-4088-a9e8-4b704bda7f9b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('1a0edeec-3c1f-48f5-a5ac-dc0f928f2e77', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'UPDATE', '{"state": "en_revision"}', '{"state": "aprobada"}', '2026-10-20 09:42:15.215944+00', NULL, 'Carga de datos de demostración', 'ce4e7f43-ca07-43f8-b58b-283db4103782', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('5b626697-9f3c-4eb6-bb25-c77f986374dc', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c0ade110-eb76-4242-ade8-7980dd974212', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0038", "predio": "e74d2236-a0bc-4942-8523-73dd76995381", "resultado": null, "area_local": 179.4, "solicitante": "a81f7da9-9ec6-4621-9615-bb97ede93564", "razon_social": "Estudio Flores 38", "observaciones": null, "fecha_solicitud": "2026-10-07", "fecha_inspeccion": null}', '2026-10-20 09:42:15.348615+00', NULL, 'Carga de datos de demostración', '1eee9ee6-aac1-4703-a877-b5d45a39b115', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('35f98e57-af74-4b4f-bf15-81a3fb2e06bb', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '829736de-17e2-4393-8925-eff86e054979', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0039", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 73.3, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Ferretería Mamani 39", "observaciones": null, "fecha_solicitud": "2026-10-10", "fecha_inspeccion": null}', '2026-10-20 09:42:15.389458+00', NULL, 'Carga de datos de demostración', 'dfeb4693-e0d6-4474-bf39-5d4805488d5c', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('078be2e4-6ab7-469b-b29a-d6fb1f07eeef', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '829736de-17e2-4393-8925-eff86e054979', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:15.403678+00', NULL, 'Carga de datos de demostración', '04cec7f0-d589-4f94-bbba-ef69b6ca2e46', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('aa7eb528-deb0-4ca2-aa63-d6d1678b1096', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '829736de-17e2-4393-8925-eff86e054979', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:15.43006+00', NULL, 'Falta plano de distribución', 'efc319dc-b183-4737-8cfa-1a07609eae54', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('39026927-0a3f-4b42-bf98-5fef4b51d3ed', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'ISSUE', NULL, NULL, '2026-10-20 09:42:15.837705+00', '212726ed-5dc5-4d9d-8c70-9e7452601211', NULL, 'ce4e7f43-ca07-43f8-b58b-283db4103782', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('ba67771b-47ac-4ee2-9056-c06e4409e77c', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'UPDATE', '{"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}', '{"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": "Aprobada el 2026-10-20", "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}', '2026-10-20 09:42:14.741177+00', NULL, 'automation ''certificar-aprobada''', 'ef275064-14de-4004-88e7-38b559523fe5', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('83e57c0f-b735-452a-8c79-d9639575e792', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'CREATE', NULL, '{"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}', '2026-10-20 09:42:14.763516+00', NULL, 'Carga de datos de demostración', 'bec5969a-7982-416a-bae3-7b0f65547733', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('e523612c-4e3f-4849-b58d-e80aed9219c7', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'UPDATE', '{"state": "borrador"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.775387+00', NULL, 'Carga de datos de demostración', '88512ce2-c5e7-4c0e-9942-2d7d4e25321b', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('9297d0cd-9479-4809-915e-a2f4bc79fdee', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'UPDATE', '{"state": "en_revision"}', '{"state": "observada"}', '2026-10-20 09:42:14.801766+00', NULL, 'Falta plano de distribución', 'd9b1be17-60f5-4055-ad72-eb7531f4caa8', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('71f350de-9638-4ea6-b8fc-6bb6cd22423a', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'UPDATE', '{"state": "observada"}', '{"state": "en_revision"}', '2026-10-20 09:42:14.819576+00', NULL, 'Carga de datos de demostración', 'a56adc14-d3c3-4879-9c60-f7853d6fca67', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('54f74def-d816-4763-aeb5-faf63bdcb59e', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000100', 'licencia', '6f89b57e-dac8-4395-ac6b-f494603836e0', 'CREATE', NULL, '{"giro": "FERRETERIA", "codigo": "LF-2026-0033", "predio": "a84b0be0-62c8-4ff6-b3a6-54857c5218b0", "resultado": null, "area_local": 155.7, "solicitante": "7c9fb903-a304-4e72-8f48-9bd9340a4d82", "razon_social": "Ferretería Medina 33", "observaciones": null, "fecha_solicitud": "2026-09-19", "fecha_inspeccion": null}', '2026-10-20 09:42:14.87814+00', NULL, 'Carga de datos de demostración', '7f114c77-3fe2-46ca-8576-17dd2acb9f12', 'api');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('759f45e5-ae0e-446a-b4b1-ebb74fa8a485', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'UPDATE', '{"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}', '{"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": "Aprobada el 2026-10-20", "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}', '2026-10-20 09:42:15.869213+00', NULL, 'automation ''certificar-aprobada''', 'ce4e7f43-ca07-43f8-b58b-283db4103782', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('b466e838-7e7d-44d8-ae89-c124808d4d3e', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'ISSUE', NULL, NULL, '2026-10-20 09:42:15.878898+00', '7b498733-ef0e-440f-8d3c-70167e1e9666', NULL, '90f5a466-9589-46c1-ab7d-737b2a24e881', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('a4d32d84-65a0-4835-8b64-e0675ab20161', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'UPDATE', '{"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}', '{"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": "Aprobada el 2026-10-20", "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}', '2026-10-20 09:42:15.913619+00', NULL, 'automation ''certificar-aprobada''', '90f5a466-9589-46c1-ab7d-737b2a24e881', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('6e39c5cb-a713-4de6-9de9-43ddae256927', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'ISSUE', NULL, NULL, '2026-10-20 09:42:15.922558+00', '698ea213-863b-4fbb-a2fb-12203b7bf1ff', NULL, '976cdb25-b8f5-4804-bb79-766ce95adf62', 'automation:certificar-aprobada');
INSERT INTO "wasichai"."audit_log" ("id", "organization_id", "user_id", "object_name", "record_id", "operation", "before_state", "after_state", "occurred_at", "document_id", "reason", "correlation_id", "source") VALUES ('12d7b8ff-efdf-47fa-8827-9e38ff6624b2', '00000000-0000-0000-0000-000000000001', NULL, 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'UPDATE', '{"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}', '{"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": "Aprobada el 2026-10-20", "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}', '2026-10-20 09:42:15.948195+00', NULL, 'automation ''certificar-aprobada''', '976cdb25-b8f5-4804-bb79-766ce95adf62', 'automation:certificar-aprobada');


--
-- Data for Name: automation_runs; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('f1b78a04-cb54-4d35-840e-2461d637ff49', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '3696eeb1-3a66-4717-a255-0bdd6feac753', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0001", "predio": "5f17835c-6d2f-4b5b-b5e5-341ccb3a574f", "resultado": null, "area_local": 228.3, "solicitante": "16b55360-6e5a-4721-acb4-7ea60bec232d", "razon_social": "Bodega Mamani 1", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0001", "predio": "5f17835c-6d2f-4b5b-b5e5-341ccb3a574f", "resultado": null, "area_local": 228.3, "solicitante": "16b55360-6e5a-4721-acb4-7ea60bec232d", "razon_social": "Bodega Mamani 1", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 739fd24e-9d7b-4289-ac44-2f99366e4878 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.515895+00', '2026-10-20 09:42:11.655996+00', '2026-10-20 09:42:11.785075+00', '1348cd6c-d146-4b63-ada3-fec411c9dd4f');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('26929c25-9317-4017-9cd8-86ba6761c221', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 6496b477-164e-4993-8da3-40ebf18f46d4 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.407191+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:12.709395+00', 'b574389e-bbf7-43dd-938d-2c6185689b4f');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('76c8c933-349c-4701-b769-760a594dee7e', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'e93a1aa3-9d89-4066-a7b8-201cd9b20a51', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0007", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 107.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Taller Rojas 7", "observaciones": null, "fecha_solicitud": "2026-07-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0007", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 107.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Taller Rojas 7", "observaciones": null, "fecha_solicitud": "2026-07-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification afc1d7f6-44ff-46b8-9f13-bf14b795dcca created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.223582+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:12.78443+00', '960c66f8-2ab0-4b05-bd94-38625acb01d4');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('a5629bb8-f7cc-4c27-b1dc-c79f00aa006d', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '24e2a46b-913c-4e70-b075-17229a78f771', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0010", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 281.1, "solicitante": "4a5537fa-da47-45c1-ae1c-c3a96d52c2c1", "razon_social": "Taller Flores 10", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0010", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 281.1, "solicitante": "4a5537fa-da47-45c1-ae1c-c3a96d52c2c1", "razon_social": "Taller Flores 10", "observaciones": null, "fecha_solicitud": "2026-07-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification f68bd0fe-b4f5-48a0-99e9-1fea56fe42b2 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.555812+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:12.745146+00', '5b9adcbb-0dc8-49a2-a2ff-482012b73408');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('6bceecb0-2995-426c-b74c-f9ab3ea7e851', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-002 on b24a4af3-3f66-42f3-8105-40671715bd89"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on b24a4af3-3f66-42f3-8105-40671715bd89"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.490604+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.142735+00', '2497fe52-8454-4ea4-bb77-c5c8631d8406');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('f34167b0-3cbb-4b85-bd03-609fec10bc48', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 69b0014e-b58e-4ce7-a13f-db9df30b6db9 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.625151+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.20446+00', '93d9da8c-b3d4-40eb-b761-0afa1070b4b1');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('3e1252d1-1b1a-418e-be82-3136b6d89a43', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 69b0014e-b58e-4ce7-a13f-db9df30b6db9 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.681763+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.679278+00', 'cb5bd1e6-7796-495c-b173-79ed9ead73d4');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('c20935b6-3293-45fc-8ca2-4bb4e104d844', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 5ac0896b-b840-48c0-8554-196044d5def6 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.720432+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:12.861093+00', 'bb36c078-ad5c-4544-9711-f51ebbf8bb7a');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('752125c1-b4b6-4c1c-a0e8-2ebf068f78a6', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-001 on ffbe8e6d-acf1-4651-a521-9a013f7a0b6b"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on ffbe8e6d-acf1-4651-a521-9a013f7a0b6b"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.011235+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.000583+00', 'bf016f13-687b-4a5c-80bd-d8c30b243e0a');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('315b90c6-0b75-43ea-baea-c6268012d91a', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 5ac0896b-b840-48c0-8554-196044d5def6 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.64487+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.038053+00', '8c975bfd-1ab4-4455-a4a0-2c3e592846bd');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('e823c77f-f999-4140-bb90-317539dc1ff1', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-003 on 59155d81-0f56-46b2-a04a-b32ec7fe7997"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on 59155d81-0f56-46b2-a04a-b32ec7fe7997"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.761449+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.261923+00', '681186e5-d773-421f-8ee1-a37b2a056651');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('e5014257-3d5a-40de-bc42-92f59f88cdf1', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'f1fab9f1-f034-4ded-be41-42f93b80f817', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0008", "predio": "a84b0be0-62c8-4ff6-b3a6-54857c5218b0", "resultado": null, "area_local": 260.3, "solicitante": "a81f7da9-9ec6-4621-9615-bb97ede93564", "razon_social": "Estudio Castillo 8", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-25", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0008", "predio": "a84b0be0-62c8-4ff6-b3a6-54857c5218b0", "resultado": null, "area_local": 260.3, "solicitante": "a81f7da9-9ec6-4621-9615-bb97ede93564", "razon_social": "Estudio Castillo 8", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-25", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 18b1c69d-6650-4543-a8d8-4baca86c4aea created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.317777+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.06873+00', 'a4e6adaf-7ab6-4f1f-bd97-c390380e1fe1');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('233aede0-0ab1-4eaa-a6e0-3b23676731c1', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '5f31a5d3-a189-4023-933f-7f1df56de85c', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0003", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 53, "solicitante": "7c9fb903-a304-4e72-8f48-9bd9340a4d82", "razon_social": "Estudio Chávez 3", "observaciones": null, "fecha_solicitud": "2026-07-10", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0003", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 53, "solicitante": "7c9fb903-a304-4e72-8f48-9bd9340a4d82", "razon_social": "Estudio Chávez 3", "observaciones": null, "fecha_solicitud": "2026-07-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 03d43d05-2404-4817-9319-dd3b135f973b created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.847671+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.332293+00', '3a8b69bf-1225-479d-b60a-87cb7e746cc5');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('f144da46-9832-442e-b60c-191aa287f461', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'f65ac4de-c341-49f3-88ba-cf3a2265a374', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0013", "predio": "e74d2236-a0bc-4942-8523-73dd76995381", "resultado": null, "area_local": 156.1, "solicitante": "681cf3b9-50de-4f12-aa8e-4093b43e3db5", "razon_social": "Botica Vargas 13", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0013", "predio": "e74d2236-a0bc-4942-8523-73dd76995381", "resultado": null, "area_local": 156.1, "solicitante": "681cf3b9-50de-4f12-aa8e-4093b43e3db5", "razon_social": "Botica Vargas 13", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 8252cfc6-a35e-4b70-a8c1-3ed8f414c508 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.861613+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.735544+00', '3df77f8f-3b45-4853-a14f-fd2a383e20d2');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('59b0a8b5-6a4c-483a-bd49-5e7474444272', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-004 on fbf8eaee-e14d-4843-9d09-b470a9653f92"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on fbf8eaee-e14d-4843-9d09-b470a9653f92"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.721108+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.795878+00', '90365f93-b2c5-4f36-86f4-b0d532bedf83');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('0d16688e-4862-4bed-b7a4-85e9c57042ba', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 9bd9758e-193f-45dd-a547-18d168da3196 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.982583+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:12.8204+00', 'e203411e-b3dc-4910-94b7-49fb9a81ae4b');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('c116cc23-0e78-4021-8430-04745b830244', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 9bd9758e-193f-45dd-a547-18d168da3196 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:11.924054+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.284706+00', 'ed8c7c55-cd00-4be7-ac3b-680d074ab672');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('aed6a951-f711-4cc4-9b0d-e9fb3ac25bb7', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 6496b477-164e-4993-8da3-40ebf18f46d4 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.459255+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.310118+00', '9f5dd56a-f8ed-4051-a3f6-13f4fd26bf48');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('d48c0623-e2fb-4e15-a34d-780cfca4ed5c', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'ecbe4879-eca3-4cf4-8f2f-25224eab99bd', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0006", "predio": "766b8f85-b5e6-4870-abe2-e6677e1e9bc5", "resultado": null, "area_local": 185.5, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Restaurante Vargas 6", "observaciones": null, "fecha_solicitud": "2026-07-19", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0006", "predio": "766b8f85-b5e6-4870-abe2-e6677e1e9bc5", "resultado": null, "area_local": 185.5, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Restaurante Vargas 6", "observaciones": null, "fecha_solicitud": "2026-07-19", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 3d118623-db26-4914-93d6-741ec21b3a0d created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.131176+00', '2026-10-20 09:42:12.658829+00', '2026-10-20 09:42:13.362085+00', 'f84853a7-eb3f-4b7e-b7c8-b2141e3325ba');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('0e69622a-7631-449a-8875-276bf273c2eb', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 91f11e29-8465-42d4-ae44-dde57b694456 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.278084+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.70745+00', '3e7c119d-78ce-4797-84fc-a808f62d11a0');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('3090e911-056a-4535-977e-ed0e0357cee8', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '9263f1a8-12a3-46aa-8f04-a1505239fa45', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0014", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 221.7, "solicitante": "5735071c-f421-4bf8-801a-ab912ab8186f", "razon_social": "Taller Castillo 14", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0014", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 221.7, "solicitante": "5735071c-f421-4bf8-801a-ab912ab8186f", "razon_social": "Taller Castillo 14", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification e5b115a3-c980-4b76-929d-82e543ae2415 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:12.984167+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.883836+00', '80c60c57-5dd9-4793-b853-e6ba88e82f05');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('2915e093-8842-4a0b-80a6-260cc737c294', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-005 on d16e695d-720a-4744-91af-aeb575bedce2"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on d16e695d-720a-4744-91af-aeb575bedce2"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.308409+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:14.012171+00', '49916294-1147-49a9-bf07-f72a478a313c');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('df9276ee-597d-44c0-8a56-42014156c195', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 332c4d0f-cdcc-47c7-a3be-159b21198ea0 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.449331+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.827222+00', 'b99f8eca-4105-40c4-a3a2-2c16103b848a');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('b0ac8130-2903-4055-a855-89859a583ce5', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '98778346-7f16-4252-be16-6781cfd7b6a2', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0015", "predio": "a26ddbbf-828d-4d0e-aa38-c46298053f2d", "resultado": null, "area_local": 208.2, "solicitante": "170f415c-27ce-4b59-9acb-027585d7f293", "razon_social": "Restaurante Torres 15", "observaciones": null, "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0015", "predio": "a26ddbbf-828d-4d0e-aa38-c46298053f2d", "resultado": null, "area_local": 208.2, "solicitante": "170f415c-27ce-4b59-9acb-027585d7f293", "razon_social": "Restaurante Torres 15", "observaciones": null, "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 352f0f23-a6d5-404b-aff5-9133731d91d2 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.119757+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.856422+00', 'f4b52fef-5365-411d-bbdb-6eb799462f45');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('bdaee4bb-58a5-4a50-9c99-c221743f334c', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 332c4d0f-cdcc-47c7-a3be-159b21198ea0 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.499307+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.898298+00', '3a0ed993-78c7-4619-ad3e-4c3b627a0485');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('d0334bab-7fa1-4da5-8a3b-fae953760d2c', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '5af20f44-aa18-476c-bf07-1b844de0b892', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0020", "predio": "540f8841-1d8c-47e7-b126-ecbbcddc2b1e", "resultado": null, "area_local": 158.5, "solicitante": "2f24c06a-1df3-4791-8330-56897fa5a0d0", "razon_social": "Estudio Medina 20", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0020", "predio": "540f8841-1d8c-47e7-b126-ecbbcddc2b1e", "resultado": null, "area_local": 158.5, "solicitante": "2f24c06a-1df3-4791-8330-56897fa5a0d0", "razon_social": "Estudio Medina 20", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 70a9d87c-5bbe-4cd4-8295-133cc8abce52 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.630513+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.92771+00', '723d25d6-bdea-434e-84ca-cd7dded68230');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('593b6b10-c5f7-4841-aeb5-93ff0d7a59c2', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '1cfb238e-0268-4a9b-91e4-cf84758797f1', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0017", "predio": "ad910484-8b39-4f3f-8195-7882b6076d87", "resultado": null, "area_local": 245.3, "solicitante": "846dbc52-4c85-4b69-a752-a035c14add2a", "razon_social": "Taller Ramos 17", "observaciones": null, "fecha_solicitud": "2026-08-25", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0017", "predio": "ad910484-8b39-4f3f-8195-7882b6076d87", "resultado": null, "area_local": 245.3, "solicitante": "846dbc52-4c85-4b69-a752-a035c14add2a", "razon_social": "Taller Ramos 17", "observaciones": null, "fecha_solicitud": "2026-08-25", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 109c900c-2347-4aa2-8fb9-de5e1819e775 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.384321+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:13.972652+00', '4cbb5c1a-1c57-4c51-a46b-9159ab52d0ad');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('01c6bbbf-980f-4932-86dd-e067e820b37a', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-006 on 9d556da0-f59b-4a79-a728-bd63536ddc45"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on 9d556da0-f59b-4a79-a728-bd63536ddc45"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.524282+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:14.079172+00', 'f9bce694-1c4e-4877-96a4-8dfebe5f64a7');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('14ffd1d4-0f41-46ae-99c2-3b3f44a59996', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '9b70de67-dde6-4eff-8940-f5665ebf8529', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0022", "predio": "4a6042dc-228f-4671-b0e3-7f340b43e685", "resultado": null, "area_local": 105.8, "solicitante": "26e36d67-2d3a-48c0-8569-aa97fa272bad", "razon_social": "Restaurante Ramos 22", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0022", "predio": "4a6042dc-228f-4671-b0e3-7f340b43e685", "resultado": null, "area_local": 105.8, "solicitante": "26e36d67-2d3a-48c0-8569-aa97fa272bad", "razon_social": "Restaurante Ramos 22", "observaciones": null, "fecha_solicitud": "2026-08-13", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification abb3dff0-2ef4-41a3-aa34-550efb454b76 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.863213+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.82341+00', '2af034c5-14ac-459f-8ab2-ac7c95b0adcc');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('8a8c0f37-2df4-4dd8-9c34-e912d97f749d', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'd16e695d-720a-4744-91af-aeb575bedce2', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 91f11e29-8465-42d4-ae44-dde57b694456 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.21644+00', '2026-10-20 09:42:13.654237+00', '2026-10-20 09:42:14.033384+00', '5bb107ae-a360-4ee3-a075-00899e3107b8');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('329ce93d-740b-4a32-b4b5-b87921b50303', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-008 on 6db3ac47-a69d-472f-8b31-50476b8fe484"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on 6db3ac47-a69d-472f-8b31-50476b8fe484"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.012018+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.805746+00', 'a7a355ac-9c93-4c0a-b597-abacabac64e9');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('eacb843e-0d20-410e-84c3-1c1fae41b7b2', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 8b1bc313-ee68-4b13-b33b-e7e8ae6b94cc created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.819576+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.783574+00', 'a56adc14-d3c3-4879-9c60-f7853d6fca67');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('50a1c4ac-2cc4-45f7-973f-ba1e02afef49', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 8b1bc313-ee68-4b13-b33b-e7e8ae6b94cc unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.775387+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.819131+00', '88512ce2-c5e7-4c0e-9942-2d7d4e25321b');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('4f5feed7-7c37-49fb-8db7-2304834d9cd4', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 95b24b01-bb8c-4e21-88e5-86bb656f0acc created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.941074+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.672426+00', 'a234da9d-3a9c-42ac-b3c2-a0d1b46c3d92');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('953cd480-874c-4c9b-b00b-04fdec51f733', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-007 on 817ac2ac-ff28-4b1f-bc10-b4381ea89a16"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on 817ac2ac-ff28-4b1f-bc10-b4381ea89a16"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.236782+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.745366+00', 'ef275064-14de-4004-88e7-38b559523fe5');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('0a72881a-d1b4-4317-b6b1-8746d9044a05', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification a4ca248a-9d03-4c37-b356-3f73bc6936dd created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.164307+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.760787+00', '8c868740-f13c-4a1f-9f67-de925c0017b9');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('642c17e0-c2ef-40aa-9783-c7c6e717cd9b', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '52b50a86-29eb-4ac6-8947-776ddd57bb8b', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0021", "predio": "3b211ef7-e67f-4a3d-9a0b-12979deb7cde", "resultado": null, "area_local": 21.9, "solicitante": "ed8caf7c-8bfe-474f-872b-46b7b39f2454", "razon_social": "Taller Chávez 21", "observaciones": null, "fecha_solicitud": "2026-08-10", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0021", "predio": "3b211ef7-e67f-4a3d-9a0b-12979deb7cde", "resultado": null, "area_local": 21.9, "solicitante": "ed8caf7c-8bfe-474f-872b-46b7b39f2454", "razon_social": "Taller Chávez 21", "observaciones": null, "fecha_solicitud": "2026-08-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 0b28b7cf-f175-4d3d-9ed2-070e916613a7 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.74494+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.842362+00', 'b0fcf7f8-05a2-4329-a445-6c093637e436');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('214f2790-6ffd-411d-b292-d80ea9849ab4', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification a4ca248a-9d03-4c37-b356-3f73bc6936dd unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.213455+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.85531+00', '3072612c-73c4-4739-ba28-f6d51a17e17e');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('966711fa-bb91-4575-9c52-950210fd35c3', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '1044e268-2c76-4923-8bab-cbeccad576c6', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0024", "predio": "73cb1aff-421a-4366-a9da-e990f6e815ee", "resultado": null, "area_local": 76.6, "solicitante": "8e6d1243-a7c5-4aac-9b83-88b463337076", "razon_social": "Restaurante Rojas 24", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0024", "predio": "73cb1aff-421a-4366-a9da-e990f6e815ee", "resultado": null, "area_local": 76.6, "solicitante": "8e6d1243-a7c5-4aac-9b83-88b463337076", "razon_social": "Restaurante Rojas 24", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-19", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification b4b91b7e-b684-48c7-9933-ef4a3fec9882 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.08506+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.871922+00', '7a4c159e-0f6a-41e4-8aad-c4fd6353ad42');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('0b43144e-bfa5-4709-9e65-bdfe2c298267', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 95b24b01-bb8c-4e21-88e5-86bb656f0acc unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:13.98865+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.886813+00', '30d148d7-f6aa-4881-a28d-7e5b7b9b46e8');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('64c3fea7-a992-46ad-b604-b7fd2f305bfe', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '54954b2b-23a1-421e-a341-21618051b2a9', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0027", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 252.6, "solicitante": "fce2430d-6b2d-4c23-a75d-45ddf38ff015", "razon_social": "Botica Chávez 27", "observaciones": null, "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0027", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 252.6, "solicitante": "fce2430d-6b2d-4c23-a75d-45ddf38ff015", "razon_social": "Botica Chávez 27", "observaciones": null, "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 115912ad-3ff1-4bf2-b6ae-6378e19cfa97 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.342463+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.690416+00', 'd8a6782b-4351-488c-8a7f-30898c9f41ce');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('addb304d-ae16-422f-b530-4c83ed8e4fbb', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'a0da9137-f268-4f34-965e-2fa6fe299f64', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0029", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 210.7, "solicitante": "68d95c39-57f5-4d1f-8595-153df4f76aa5", "razon_social": "Restaurante Cáceres 29", "observaciones": null, "fecha_solicitud": "2026-09-07", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0029", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 210.7, "solicitante": "68d95c39-57f5-4d1f-8595-153df4f76aa5", "razon_social": "Restaurante Cáceres 29", "observaciones": null, "fecha_solicitud": "2026-09-07", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 585873b5-4332-4f32-9b26-75e0ead3c150 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.55199+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.709696+00', '2f5bef5d-726c-4aea-b149-de7177ee9fae');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('5170389f-97e7-4d40-8dcf-10a7c5c8cac4', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 431cf406-bd19-402a-a62a-57a6785f52ac created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.616529+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.908838+00', '552b5adc-a36e-4cc3-86a8-f8be2a685f6f');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('4b5c9985-da22-416d-a8ca-e6e85c16531c', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'eba4a091-eca0-43b9-b20a-71e7379c9157', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0028", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 27.1, "solicitante": "5c59a1f5-97ae-45cf-9ef3-f142f8a41f75", "razon_social": "Estudio Paredes 28", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-04", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0028", "predio": "f579c281-f200-40e1-96cd-8deaa7f0e553", "resultado": null, "area_local": 27.1, "solicitante": "5c59a1f5-97ae-45cf-9ef3-f142f8a41f75", "razon_social": "Estudio Paredes 28", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 91338ce2-e1e1-4e0d-9b81-dddd959e810a created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.43484+00', '2026-10-20 09:42:14.653129+00', '2026-10-20 09:42:14.934565+00', 'c17f857a-3522-4e7c-b207-ba0f5ff12491');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('d33122bf-7217-4292-81c9-3dec27e28121', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification 431cf406-bd19-402a-a62a-57a6785f52ac unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.660237+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.831804+00', '9a9f6c45-c153-4dbf-81fc-7c17befea3a9');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('62abc9f5-0c0c-4ec2-84c2-1f7552cbf611', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '08afe0b4-153d-42fd-9aa7-ee1d572e71f4', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0036", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 193.8, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Estudio Vargas 36", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "BODEGA", "codigo": "LF-2026-0036", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 193.8, "solicitante": "79067a68-4339-4d4b-a7b2-f2c06f9ebfa9", "razon_social": "Estudio Vargas 36", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-01", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 5ff6121e-4af3-4d12-b0b6-28ac29db8a2d created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.092473+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.683169+00', 'b3b06497-c4d3-494d-ad1c-90401d65112d');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('b64257dc-97af-42ae-88f1-8be26875f907', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "subsanar"}', '[{"action": "NOTIFY", "detail": "notification ff4dafb0-e2f2-4ff0-9f89-8dacb28b5138 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.193851+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.750676+00', '83725348-4f48-4088-a9e8-4b704bda7f9b');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('5ba9fa92-2ba2-40f9-b0bc-ed7a7770d137', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '3adf714a-ee09-4472-be0a-23ce2ce93250', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "TALLER", "codigo": "LF-2026-0035", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 119.8, "solicitante": "50ee49d8-325e-4564-ae56-a5de0709a456", "razon_social": "Taller Salazar 35", "observaciones": null, "fecha_solicitud": "2026-09-25", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "TALLER", "codigo": "LF-2026-0035", "predio": "3a7aa199-1854-4416-97be-4a329cbc3c78", "resultado": null, "area_local": 119.8, "solicitante": "50ee49d8-325e-4564-ae56-a5de0709a456", "razon_social": "Taller Salazar 35", "observaciones": null, "fecha_solicitud": "2026-09-25", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 835d28d0-c495-4571-8c0a-4fa88cf3dd76 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.007954+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.805616+00', '4a9598e2-2179-41f8-a563-fa11b1e74fe5');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('8a647791-ea6e-4ab1-9a77-53072524c9a4', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-009 on 0e114cf0-753a-4be5-ba62-0ff1ede9709a"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on 0e114cf0-753a-4be5-ba62-0ff1ede9709a"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.215944+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.873861+00', 'ce4e7f43-ca07-43f8-b58b-283db4103782');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('dd5a3b85-0a0f-42ff-ab0f-b34c00345127', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-010 on c9ef1c08-1768-4d35-8409-377d6225deb7"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on c9ef1c08-1768-4d35-8409-377d6225deb7"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.838432+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.917945+00', '90f5a466-9589-46c1-ab7d-737b2a24e881');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('d30bef6d-3146-45b9-98a2-54cfd1e89a27', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '829736de-17e2-4393-8925-eff86e054979', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "FERRETERIA", "codigo": "LF-2026-0039", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 73.3, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Ferretería Mamani 39", "observaciones": null, "fecha_solicitud": "2026-10-10", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "FERRETERIA", "codigo": "LF-2026-0039", "predio": "4e105a70-c0fc-46c9-8d80-51532fa3ac8c", "resultado": null, "area_local": 73.3, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Ferretería Mamani 39", "observaciones": null, "fecha_solicitud": "2026-10-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification 73dcdbd5-df15-4592-b6f7-29515f0450b5 created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.403678+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.703491+00', '04cec7f0-d589-4f94-bbba-ef69b6ca2e46');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('89ec9ed6-1277-4ed5-b109-1b9756469927', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', 'df7c462b-84f2-4283-899f-78e434e54c87', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "OFICINA", "codigo": "LF-2026-0034", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 288.8, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Ferretería Vargas 34", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "OFICINA", "codigo": "LF-2026-0034", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 288.8, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Ferretería Vargas 34", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification ab4bf66d-2d50-44a3-b53a-26f78593697e created for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.928245+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.729595+00', '067a8351-4e3e-4671-8139-21cf52dfcd76');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('806a5a0b-fe04-4a4c-a3a6-9d4219ac0ce9', '00000000-0000-0000-0000-000000000001', 'c706ef8f-7f12-4c2a-873e-d76aec2852fd', 'licencia', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "state": "en_revision", "before": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "enviar"}', '[{"action": "NOTIFY", "detail": "notification ff4dafb0-e2f2-4ff0-9f89-8dacb28b5138 unchanged for role:REVISOR"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.152912+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.763572+00', '469d43a7-1a8b-4276-b945-db5702c4f866');
INSERT INTO "wasichai"."automation_runs" ("id", "organization_id", "automation_id", "object_name", "record_id", "trigger_type", "status", "depth", "payload", "steps", "error", "attempts", "user_id", "created_at", "started_at", "finished_at", "correlation_id") VALUES ('97d71c28-5bdf-488d-9db0-5bdff43c9a1b', '00000000-0000-0000-0000-000000000001', 'b74c7faf-c479-4164-8407-5b3fc5a7fe9a', 'licencia', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'STATE_ENTERED', 'SUCCEEDED', 0, '{"after": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "state": "aprobada", "before": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "objectId": "865abea1-bc63-4076-a7cd-f1b8cc4c1f86", "transition": "aprobar"}', '[{"action": "GENERATE_DOCUMENT", "detail": "issued CLF-2026-011 on bdb53c41-7a9c-4101-8eac-4af6c1a643ba"}, {"action": "UPDATE_FIELD", "detail": "licencia.resultado = ''Aprobada el 2026-10-20'' on bdb53c41-7a9c-4101-8eac-4af6c1a643ba"}]', NULL, 1, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:14.681033+00', '2026-10-20 09:42:15.654263+00', '2026-10-20 09:42:15.953041+00', '976cdb25-b8f5-4804-bb79-766ce95adf62');


--
-- Data for Name: automations; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."automations" ("id", "organization_id", "object_id", "name", "label", "enabled", "definition", "created_at", "updated_at") VALUES ('b74c7faf-c479-4164-8407-5b3fc5a7fe9a', '00000000-0000-0000-0000-000000000001', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'certificar-aprobada', 'Emitir certificado al aprobar', true, '{"actions": [{"url": null, "type": "GENERATE_DOCUMENT", "field": null, "value": null, "values": {}, "documentType": "certificado", "targetObject": null}, {"url": null, "type": "UPDATE_FIELD", "field": "resultado", "value": "Aprobada el {{today}}", "values": {}, "documentType": null, "targetObject": null}], "trigger": {"type": "STATE_ENTERED", "state": "aprobada", "transition": null}, "conditions": []}', '2026-10-20 09:42:07.871014+00', '2026-10-20 09:42:07.871014+00');
INSERT INTO "wasichai"."automations" ("id", "organization_id", "object_id", "name", "label", "enabled", "definition", "created_at", "updated_at") VALUES ('c706ef8f-7f12-4c2a-873e-d76aec2852fd', '00000000-0000-0000-0000-000000000001', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'avisar-revision', 'Avisar a revisores', true, '{"actions": [{"to": "role:REVISOR", "url": null, "body": "{{razon_social}} espera revisión técnica.", "kind": "INFO", "type": "NOTIFY", "field": null, "title": "Licencia {{codigo}} pendiente de revisión", "value": null, "values": {}, "documentType": null, "targetObject": null}], "trigger": {"type": "STATE_ENTERED", "state": "en_revision", "transition": null}, "conditions": []}', '2026-10-20 09:42:08.073712+00', '2026-10-20 09:42:08.073712+00');


--
-- Data for Name: custom_fields; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('38733904-0c3c-42dd-9022-34bcae5b3140', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'codigo', 'Código', 'TEXT', 'codigo', true, true, NULL, NULL, 0, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('f981598c-1e5c-4ca8-a8d2-41d9e70b9371', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'nombre', 'Nombre o razón social', 'TEXT', 'nombre', true, false, NULL, NULL, 1, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('e10816c8-821d-42d9-874d-7f0786bc9d81', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'tipo', 'Tipo', 'ENUM', 'tipo', true, false, NULL, NULL, 2, NULL, '["PERSONA_NATURAL", "PERSONA_JURIDICA"]', NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('e0376d51-a061-4585-ae75-f39580841182', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'documento', 'DNI / RUC', 'TEXT', 'documento', false, false, NULL, NULL, 3, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('426df71a-3dab-4286-ac05-01ec31ee1bff', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'email', 'Correo', 'EMAIL', 'email', false, false, NULL, NULL, 4, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('a4b63b99-e288-4871-b157-f2a0a19853bd', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'telefono', 'Teléfono', 'TEXT', 'telefono', false, false, NULL, NULL, 5, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('127a0505-f9c6-40e9-b9a8-bd178ab4f794', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'direccion', 'Dirección fiscal', 'TEXT', 'direccion', false, false, NULL, NULL, 6, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('a2e7e0e8-0df6-4a64-a88b-9126c62a4315', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'activo', 'Activo', 'BOOLEAN', 'activo', false, false, 'true', NULL, 7, NULL, NULL, NULL, true, true, '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('f93d7958-d764-4380-91a0-77273bbcab3b', 'd4333a27-6895-4ced-8cca-808a5c439298', 'codigo', 'Código catastral', 'TEXT', 'codigo', true, true, NULL, NULL, 0, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('84e3255f-2f22-4fd3-b6ed-bf1b44583a60', 'd4333a27-6895-4ced-8cca-808a5c439298', 'direccion', 'Dirección', 'TEXT', 'direccion', false, false, NULL, NULL, 1, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('0aba8620-5135-43b0-b4c1-7cf0286f1ec1', 'd4333a27-6895-4ced-8cca-808a5c439298', 'area', 'Área (m²)', 'DECIMAL', 'area', false, false, NULL, NULL, 2, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('600cdd5b-ab75-40fa-ad52-cfcf2baaf45e', 'd4333a27-6895-4ced-8cca-808a5c439298', 'uso', 'Uso', 'ENUM', 'uso', false, false, NULL, NULL, 3, NULL, '["RESIDENCIAL", "COMERCIAL", "INDUSTRIAL", "AGRICOLA"]', NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('6465b0e3-60bd-428d-86ed-89a5e4e11437', 'd4333a27-6895-4ced-8cca-808a5c439298', 'valor_autovaluo', 'Valor de autovalúo (S/)', 'DECIMAL', 'valor_autovaluo', false, false, NULL, NULL, 4, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('ed1b92d0-a489-4a6d-9b3a-9569fa412aa2', 'd4333a27-6895-4ced-8cca-808a5c439298', 'lote', 'Lote', 'GEOMETRY', 'lote', false, false, NULL, NULL, 5, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', false, NULL, 'POLYGON', 4326, 2, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('d805a846-c47c-423e-a4ac-08adfc0263eb', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'codigo', 'Expediente', 'TEXT', 'codigo', true, true, NULL, NULL, 0, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('8f4b7023-cda9-420f-adb8-045f08ccf9b0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'razon_social', 'Establecimiento', 'TEXT', 'razon_social', true, false, NULL, NULL, 1, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('a4129162-a8d7-4ffc-9d53-a9f05cb03e26', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'giro', 'Giro', 'ENUM', 'giro', true, false, NULL, NULL, 2, NULL, '["BODEGA", "RESTAURANTE", "FARMACIA", "FERRETERIA", "OFICINA", "TALLER"]', NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('15ecc451-3aab-492f-bb88-9d293dbd9d45', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'area_local', 'Área del local (m²)', 'DECIMAL', 'area_local', false, false, NULL, NULL, 3, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('e4cf5c1d-8e13-4650-a5e3-859ee0c7f41c', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'fecha_solicitud', 'Fecha de solicitud', 'DATE', 'fecha_solicitud', false, false, NULL, NULL, 4, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('00d6baf0-ba01-4918-a973-5afed3fe7c4a', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'fecha_inspeccion', 'Inspección', 'DATETIME', 'fecha_inspeccion', false, false, NULL, NULL, 5, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, 'America/Lima', NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('a37fc401-a981-47d1-ad32-4eefdf5acdd9', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'observaciones', 'Observaciones', 'LONG_TEXT', 'observaciones', false, false, NULL, NULL, 6, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('752d1cff-52c7-48a5-a20c-ef7b740922fe', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'resultado', 'Resultado', 'TEXT', 'resultado', false, false, NULL, NULL, 7, NULL, NULL, NULL, true, true, '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('9c432291-a1d9-4a3e-a24e-946b0efdd722', 'd4333a27-6895-4ced-8cca-808a5c439298', 'titular', 'Titular', 'RELATION', 'titular', false, false, NULL, NULL, 6, NULL, NULL, 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', true, true, '2026-10-20 09:42:05.375933+00', '2026-10-20 09:42:05.375933+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('fad15abe-f0e2-485c-bb38-ecf71af1787a', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'solicitante', 'Solicitante', 'RELATION', 'solicitante', false, false, NULL, NULL, 8, NULL, NULL, 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', true, true, '2026-10-20 09:42:05.512014+00', '2026-10-20 09:42:05.512014+00', false, NULL, NULL, NULL, NULL, NULL, NULL);
INSERT INTO "wasichai"."custom_fields" ("id", "object_id", "name", "label", "type", "column_name", "required", "is_unique", "default_value", "description", "position", "validation", "enum_options", "relation_target_object_id", "visible", "editable", "created_at", "updated_at", "indexed", "time_zone", "geometry_type", "srid", "dimension", "file_max_bytes", "file_content_types") VALUES ('b57dfdee-1cc8-4598-a526-f9250f043f21', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'predio', 'Predio', 'RELATION', 'predio', false, false, NULL, NULL, 9, NULL, NULL, 'd4333a27-6895-4ced-8cca-808a5c439298', true, true, '2026-10-20 09:42:05.642511+00', '2026-10-20 09:42:05.642511+00', false, NULL, NULL, NULL, NULL, NULL, NULL);


--
-- Data for Name: custom_objects; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."custom_objects" ("id", "organization_id", "name", "label", "plural_label", "description", "enabled", "physical_table", "created_at", "updated_at", "indexes", "unique_constraints", "append_only", "api_only", "requires_reason") VALUES ('e536b189-e9cf-4aa6-ac47-b7dbfa79d182', '00000000-0000-0000-0000-000000000001', 'contribuyente', 'Contribuyente', 'Contribuyentes', 'Persona natural o jurídica con obligaciones municipales', true, 'contribuyente__00000000', '2026-10-20 09:42:04.320986+00', '2026-10-20 09:42:04.320986+00', '[]', '[]', false, false, false);
INSERT INTO "wasichai"."custom_objects" ("id", "organization_id", "name", "label", "plural_label", "description", "enabled", "physical_table", "created_at", "updated_at", "indexes", "unique_constraints", "append_only", "api_only", "requires_reason") VALUES ('d4333a27-6895-4ced-8cca-808a5c439298', '00000000-0000-0000-0000-000000000001', 'predio', 'Predio', 'Predios', 'Unidad catastral del distrito', true, 'predio__00000000', '2026-10-20 09:42:05.011297+00', '2026-10-20 09:42:05.011297+00', '[]', '[]', false, false, false);
INSERT INTO "wasichai"."custom_objects" ("id", "organization_id", "name", "label", "plural_label", "description", "enabled", "physical_table", "created_at", "updated_at", "indexes", "unique_constraints", "append_only", "api_only", "requires_reason") VALUES ('865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '00000000-0000-0000-0000-000000000001', 'licencia', 'Licencia de funcionamiento', 'Licencias de funcionamiento', 'Solicitud de licencia para un establecimiento comercial', true, 'licencia__00000000', '2026-10-20 09:42:05.192478+00', '2026-10-20 09:42:05.192478+00', '[]', '[]', false, false, true);


--
-- Data for Name: document_counters; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."document_counters" ("document_type_id", "year", "next") VALUES ('a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', 2026, 12);


--
-- Data for Name: document_types; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."document_types" ("id", "organization_id", "object_id", "name", "label", "prefix", "template", "created_at", "updated_at") VALUES ('a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '00000000-0000-0000-0000-000000000001', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'certificado', 'Certificado de licencia', 'CLF', '{"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}', '2026-10-20 09:42:07.673643+00', '2026-10-20 09:42:07.673643+00');


--
-- Data for Name: documents; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('1b0d08d6-107f-481f-b651-fc5e2688bf76', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b', 'CLF-2026-001', 2026, 1, 'VALID', '{"number": "CLF-2026-001", "values": {"giro": "OFICINA", "codigo": "LF-2026-0004", "predio": "8bf9bc6a-a5b4-4b9c-81e6-5c1c6c4c8b95", "resultado": null, "area_local": 264.6, "solicitante": "c4551f79-e2fa-4de7-a374-59e6ae3ea37c", "razon_social": "Taller Castillo 4", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-07-13", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:12.910339896Z", "platform": {"id": "ffbe8e6d-acf1-4651-a521-9a013f7a0b6b", "now": "2026-10-20T09:42:12.910339896Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-001", "documentPrefix": "CLF", "documentSerial": "2026-001"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:12.878634+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('7e110b83-d3f6-4e4b-85c9-3cb41f610aa8', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'b24a4af3-3f66-42f3-8105-40671715bd89', 'CLF-2026-002', 2026, 2, 'VALID', '{"number": "CLF-2026-002", "values": {"giro": "FERRETERIA", "codigo": "LF-2026-0009", "predio": "8c717ce7-5524-4560-99bd-5b02c47f3fce", "resultado": null, "area_local": 151.7, "solicitante": "12f034e4-6351-4fa7-aca0-bfef1afb6f29", "razon_social": "Estudio Quispe 9", "observaciones": null, "fecha_solicitud": "2026-07-01", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:13.097170764Z", "platform": {"id": "b24a4af3-3f66-42f3-8105-40671715bd89", "now": "2026-10-20T09:42:13.097170764Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-002", "documentPrefix": "CLF", "documentSerial": "2026-002"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:13.080477+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('dcd6f108-b32b-4465-b48b-771071b929bd', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '59155d81-0f56-46b2-a04a-b32ec7fe7997', 'CLF-2026-003', 2026, 3, 'VALID', '{"number": "CLF-2026-003", "values": {"giro": "FARMACIA", "codigo": "LF-2026-0002", "predio": "a9f7e251-496c-44f1-9f40-12f253554622", "resultado": null, "area_local": 196.4, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Bodega Vargas 2", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:13.222863582Z", "platform": {"id": "59155d81-0f56-46b2-a04a-b32ec7fe7997", "now": "2026-10-20T09:42:13.222863582Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-003", "documentPrefix": "CLF", "documentSerial": "2026-003"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:13.211874+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('a90e3d81-3e7a-4262-a44e-66c818ceae18', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'fbf8eaee-e14d-4843-9d09-b470a9653f92', 'CLF-2026-004', 2026, 4, 'VALID', '{"number": "CLF-2026-004", "values": {"giro": "TALLER", "codigo": "LF-2026-0011", "predio": "d1548202-a8ae-4a63-9196-4f8894264a7b", "resultado": null, "area_local": 100.2, "solicitante": "63b995af-c0ef-4290-8981-eab21020a4e4", "razon_social": "Estudio Mamani 11", "observaciones": null, "fecha_solicitud": "2026-07-07", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:13.760436258Z", "platform": {"id": "fbf8eaee-e14d-4843-9d09-b470a9653f92", "now": "2026-10-20T09:42:13.760436258Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-004", "documentPrefix": "CLF", "documentSerial": "2026-004"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:13.742391+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('8905e113-fa86-468c-837c-bb1da101f10c', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'd16e695d-720a-4744-91af-aeb575bedce2', 'CLF-2026-005', 2026, 5, 'VALID', '{"number": "CLF-2026-005", "values": {"giro": "OFICINA", "codigo": "LF-2026-0016", "predio": "46222881-cddc-4be0-874c-331c2c07cdb0", "resultado": null, "area_local": 170.6, "solicitante": "aac7c6c4-5c4f-4228-8a96-4786e71ccf9e", "razon_social": "Taller Castillo 16", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-08-22", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:13.984969492Z", "platform": {"id": "d16e695d-720a-4744-91af-aeb575bedce2", "now": "2026-10-20T09:42:13.984969492Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-005", "documentPrefix": "CLF", "documentSerial": "2026-005"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:13.97764+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('f7f6acec-f33f-43fa-adae-057cce566bb5', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '9d556da0-f59b-4a79-a728-bd63536ddc45', 'CLF-2026-006', 2026, 6, 'VALID', '{"number": "CLF-2026-006", "values": {"giro": "BODEGA", "codigo": "LF-2026-0018", "predio": "2c0c3d6c-c603-4757-8a69-ceaac3546e64", "resultado": null, "area_local": 260.4, "solicitante": "4bf492c9-9874-443c-9f24-74d0643acb92", "razon_social": "Estudio Torres 18", "observaciones": null, "fecha_solicitud": "2026-08-01", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:14.050200079Z", "platform": {"id": "9d556da0-f59b-4a79-a728-bd63536ddc45", "now": "2026-10-20T09:42:14.050200079Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-006", "documentPrefix": "CLF", "documentSerial": "2026-006"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:14.041628+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('1126d591-377c-4d6a-a5a4-48db6ff65c07', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16', 'CLF-2026-007', 2026, 7, 'VALID', '{"number": "CLF-2026-007", "values": {"giro": "RESTAURANTE", "codigo": "LF-2026-0025", "predio": "a5f07856-8281-4392-9a55-720ac56fe5fe", "resultado": null, "area_local": 104.7, "solicitante": "16f8eb2e-04bf-40f2-8ef9-cfb6d8c7cdd3", "razon_social": "Botica Medina 25", "observaciones": null, "fecha_solicitud": "2026-09-22", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:14.723186977Z", "platform": {"id": "817ac2ac-ff28-4b1f-bc10-b4381ea89a16", "now": "2026-10-20T09:42:14.723186977Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-007", "documentPrefix": "CLF", "documentSerial": "2026-007"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:14.715224+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('d74b9138-479f-432d-b7c8-bffe5ca85614', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '6db3ac47-a69d-472f-8b31-50476b8fe484', 'CLF-2026-008', 2026, 8, 'VALID', '{"number": "CLF-2026-008", "values": {"giro": "TALLER", "codigo": "LF-2026-0023", "predio": "ba9b3af8-e337-4d4d-8de6-e1b8f926b06d", "resultado": null, "area_local": 284.7, "solicitante": "21d3ba3c-41b9-42d9-9f6e-0a12909a6ac7", "razon_social": "Botica Salazar 23", "observaciones": null, "fecha_solicitud": "2026-08-16", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:14.778707150Z", "platform": {"id": "6db3ac47-a69d-472f-8b31-50476b8fe484", "now": "2026-10-20T09:42:14.778707150Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-008", "documentPrefix": "CLF", "documentSerial": "2026-008"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:14.767944+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('212726ed-5dc5-4d9d-8c70-9e7452601211', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '0e114cf0-753a-4be5-ba62-0ff1ede9709a', 'CLF-2026-009', 2026, 9, 'VALID', '{"number": "CLF-2026-009", "values": {"giro": "RESTAURANTE", "codigo": "LF-2026-0037", "predio": "227e7b9c-ed20-40f3-9d97-c38c75f06eb8", "resultado": null, "area_local": 59.2, "solicitante": "7b2b6529-6c81-444f-950d-1635e123803d", "razon_social": "Estudio Ramos 37", "observaciones": null, "fecha_solicitud": "2026-10-04", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:15.845383026Z", "platform": {"id": "0e114cf0-753a-4be5-ba62-0ff1ede9709a", "now": "2026-10-20T09:42:15.845383026Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-009", "documentPrefix": "CLF", "documentSerial": "2026-009"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:15.837705+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('7b498733-ef0e-440f-8d3c-70167e1e9666', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'c9ef1c08-1768-4d35-8409-377d6225deb7', 'CLF-2026-010', 2026, 10, 'VALID', '{"number": "CLF-2026-010", "values": {"giro": "FARMACIA", "codigo": "LF-2026-0032", "predio": "fc88650a-e9dc-4122-9530-9466e16c5394", "resultado": null, "area_local": 218.9, "solicitante": "d7cbcddf-68ca-42a4-b3b3-00f5ce99a573", "razon_social": "Botica Vargas 32", "observaciones": "Presentar plano de distribución actualizado y certificado de defensa civil.", "fecha_solicitud": "2026-09-16", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:15.885906352Z", "platform": {"id": "c9ef1c08-1768-4d35-8409-377d6225deb7", "now": "2026-10-20T09:42:15.885906352Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-010", "documentPrefix": "CLF", "documentSerial": "2026-010"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:15.878898+00', NULL);
INSERT INTO "wasichai"."documents" ("id", "organization_id", "document_type_id", "object_id", "record_id", "number", "year", "sequence", "status", "snapshot", "issued_at", "issued_by") VALUES ('698ea213-863b-4fbb-a2fb-12203b7bf1ff', '00000000-0000-0000-0000-000000000001', 'a15eb3ec-cfcb-404f-a008-3e48b6bc3ca0', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba', 'CLF-2026-011', 2026, 11, 'VALID', '{"number": "CLF-2026-011", "values": {"giro": "BODEGA", "codigo": "LF-2026-0030", "predio": "fb03a88f-1e62-42fc-bfe6-aa5fa4f2fa1b", "resultado": null, "area_local": 120.1, "solicitante": "00122049-9609-495d-84c4-f526d8cc0cee", "razon_social": "Restaurante Ramos 30", "observaciones": null, "fecha_solicitud": "2026-09-10", "fecha_inspeccion": null}, "related": {}, "issuedAt": "2026-10-20T09:42:15.928735572Z", "platform": {"id": "bdb53c41-7a9c-4101-8eac-4af6c1a643ba", "now": "2026-10-20T09:42:15.928735572Z", "user": "", "today": "2026-10-20", "documentName": "Certificado de licencia", "documentNumber": "CLF-2026-011", "documentPrefix": "CLF", "documentSerial": "2026-011"}, "template": {"text": null, "type": "doc", "attrs": null, "marks": null, "content": [{"text": null, "type": "heading", "attrs": {"level": 2}, "marks": null, "content": [{"text": null, "type": "platformValue", "attrs": {"value": "documentName"}, "marks": null, "content": null}, {"text": " ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "documentNumber"}, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "La Subgerencia de Licencias Comerciales del Distrito Demo certifica que el establecimiento ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "razon_social"}, "marks": null, "content": null}, {"text": ", expediente ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "codigo"}, "marks": null, "content": null}, {"text": ", cuenta con licencia de funcionamiento para el giro ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "giro"}, "marks": null, "content": null}, {"text": ".", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Área autorizada: ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "objectField", "attrs": {"field": "area_local"}, "marks": null, "content": null}, {"text": " m².", "type": "text", "attrs": null, "marks": null, "content": null}]}, {"text": null, "type": "paragraph", "attrs": null, "marks": null, "content": [{"text": "Emitido el ", "type": "text", "attrs": null, "marks": null, "content": null}, {"text": null, "type": "platformValue", "attrs": {"value": "today"}, "marks": null, "content": null}, {"text": ". Documento de demostración sin validez legal.", "type": "text", "attrs": null, "marks": null, "content": null}]}]}, "objectName": "licencia", "objectLabel": "Licencia de funcionamiento"}', '2026-10-20 09:42:15.922558+00', NULL);


--
-- Data for Name: field_permissions; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."field_permissions" ("role_id", "field_id", "can_read", "can_write") VALUES ('1cbd090a-8508-442a-9c20-3d53077e7050', 'e0376d51-a061-4585-ae75-f39580841182', false, false);
INSERT INTO "wasichai"."field_permissions" ("role_id", "field_id", "can_read", "can_write") VALUES ('1cbd090a-8508-442a-9c20-3d53077e7050', 'a4b63b99-e288-4871-b157-f2a0a19853bd', false, false);


--
-- Data for Name: flyway_history_automation; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_automation" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:57.413494', 0, true);
INSERT INTO "wasichai"."flyway_history_automation" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'automation', 'SQL', 'V1__automation.sql', -1368452162, 'wasichai', '2026-10-20 09:41:57.443628', 20, true);
INSERT INTO "wasichai"."flyway_history_automation" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (3, '2', 'run correlation id', 'SQL', 'V2__run_correlation_id.sql', -463640022, 'wasichai', '2026-10-20 09:41:57.480243', 3, true);


--
-- Data for Name: flyway_history_core; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"wasichai"', NULL, 'wasichai', '2026-10-20 09:41:56.708003', 0, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '1', 'core', 'SQL', 'V1__core.sql', 204732223, 'wasichai', '2026-10-20 09:41:56.739887', 96, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '2', 'user preferences', 'SQL', 'V2__user_preferences.sql', 80208520, 'wasichai', '2026-10-20 09:41:56.894239', 13, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (3, '3', 'declared indexes', 'SQL', 'V3__declared_indexes.sql', 923791217, 'wasichai', '2026-10-20 09:41:56.921273', 5, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (4, '4', 'unique constraints', 'SQL', 'V4__unique_constraints.sql', 1273413581, 'wasichai', '2026-10-20 09:41:56.941653', 5, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (5, '5', 'object write rules', 'SQL', 'V5__object_write_rules.sql', -1713314948, 'wasichai', '2026-10-20 09:41:56.96176', 5, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (6, '6', 'change reason', 'SQL', 'V6__change_reason.sql', 265114032, 'wasichai', '2026-10-20 09:41:56.977945', 5, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (7, '7', 'object actions', 'SQL', 'V7__object_actions.sql', 373549492, 'wasichai', '2026-10-20 09:41:56.994219', 15, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (8, '8', 'service accounts', 'SQL', 'V8__service_accounts.sql', 1133483848, 'wasichai', '2026-10-20 09:41:57.023234', 9, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (9, '9', 'org units', 'SQL', 'V9__org_units.sql', 137589276, 'wasichai', '2026-10-20 09:41:57.046446', 20, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (10, '10', 'audit origin', 'SQL', 'V10__audit_origin.sql', 862691493, 'wasichai', '2026-10-20 09:41:57.08199', 7, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (11, '12', 'audit user index', 'SQL', 'V12__audit_user_index.sql', 963889276, 'wasichai', '2026-10-20 09:41:57.101241', 4, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (12, '13', 'audit log immutable', 'SQL', 'V13__audit_log_immutable.sql', 61268298, 'wasichai', '2026-10-20 09:41:57.120821', 7, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (13, '15', 'manage tenants', 'SQL', 'V15__manage_tenants.sql', 1413472768, 'wasichai', '2026-10-20 09:41:57.137733', 7, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (14, '16', 'idempotency keys', 'SQL', 'V16__idempotency_keys.sql', -1338316939, 'wasichai', '2026-10-20 09:41:57.155488', 8, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (15, '17', 'tokens valid after', 'SQL', 'V17__tokens_valid_after.sql', 527857341, 'wasichai', '2026-10-20 09:41:57.174217', 2, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (16, '18', 'field time zone', 'SQL', 'V18__field_time_zone.sql', -1950384700, 'wasichai', '2026-10-20 09:41:57.18324', 3, true);
INSERT INTO "wasichai"."flyway_history_core" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (17, NULL, 'audit purge role', 'SQL', 'R__audit_purge_role.sql', 349278008, 'wasichai', '2026-10-20 09:41:57.192867', 3, true);


--
-- Data for Name: flyway_history_core_seed; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_core_seed" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:57.298755', 0, true);
INSERT INTO "wasichai"."flyway_history_core_seed" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'seed dev', 'SQL', 'V1__seed_dev.sql', -1367678973, 'wasichai', '2026-10-20 09:41:57.332337', 12, true);


--
-- Data for Name: flyway_history_documents; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_documents" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:57.549701', 0, true);
INSERT INTO "wasichai"."flyway_history_documents" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'documents', 'SQL', 'V1__documents.sql', -205391831, 'wasichai', '2026-10-20 09:41:57.577904', 25, true);


--
-- Data for Name: flyway_history_files; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_files" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:58.881509', 0, true);
INSERT INTO "wasichai"."flyway_history_files" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'files', 'SQL', 'V1__files.sql', -1317710966, 'wasichai', '2026-10-20 09:41:58.914914', 13, true);
INSERT INTO "wasichai"."flyway_history_files" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (3, '2', 'platform run', 'SQL', 'V2__platform_run.sql', 1329592662, 'wasichai', '2026-10-20 09:41:58.939037', 3, true);


--
-- Data for Name: flyway_history_forms; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_forms" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:57.670553', 0, true);
INSERT INTO "wasichai"."flyway_history_forms" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'forms', 'SQL', 'V1__forms.sql', -2101118130, 'wasichai', '2026-10-20 09:41:57.702025', 8, true);


--
-- Data for Name: flyway_history_gis; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_gis" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:57.773974', 0, true);
INSERT INTO "wasichai"."flyway_history_gis" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'gis', 'SQL', 'V1__gis.sql', -1721827959, 'wasichai', '2026-10-20 09:41:57.805822', 447, true);


--
-- Data for Name: flyway_history_notifications; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_notifications" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:58.338785', 0, true);
INSERT INTO "wasichai"."flyway_history_notifications" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'notifications', 'SQL', 'V1__notifications.sql', -1284748126, 'wasichai', '2026-10-20 09:41:58.362877', 35, true);
INSERT INTO "wasichai"."flyway_history_notifications" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (3, '2', 'deliveries', 'SQL', 'V2__deliveries.sql', 662786185, 'wasichai', '2026-10-20 09:41:58.41375', 19, true);


--
-- Data for Name: flyway_history_pages; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_pages" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:58.502644', 0, true);
INSERT INTO "wasichai"."flyway_history_pages" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'pages', 'SQL', 'V1__pages.sql', 721389301, 'wasichai', '2026-10-20 09:41:58.533265', 13, true);


--
-- Data for Name: flyway_history_views; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_views" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:58.631767', 0, true);
INSERT INTO "wasichai"."flyway_history_views" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'views', 'SQL', 'V1__views.sql', -215931330, 'wasichai', '2026-10-20 09:41:58.663629', 11, true);


--
-- Data for Name: flyway_history_workflow; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."flyway_history_workflow" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (1, '0', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>', NULL, 'wasichai', '2026-10-20 09:41:58.7408', 0, true);
INSERT INTO "wasichai"."flyway_history_workflow" ("installed_rank", "version", "description", "type", "script", "checksum", "installed_by", "installed_on", "execution_time", "success") VALUES (2, '1', 'workflow', 'SQL', 'V1__workflow.sql', -1631863739, 'wasichai', '2026-10-20 09:41:58.769817', 18, true);


--
-- Data for Name: forms; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."forms" ("id", "object_id", "name", "label", "definition", "created_at", "updated_at", "organization_id") VALUES ('4cd0199d-c853-44f8-b458-6bd8ef1509b4', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'solicitud', 'Solicitud de licencia', '{"sections": [{"title": "Identificación", "fields": ["codigo", "razon_social", "giro", "solicitante", "predio"]}, {"title": "Local", "fields": ["area_local", "fecha_solicitud", "fecha_inspeccion"]}, {"title": "Evaluación", "fields": ["observaciones", "resultado"]}]}', '2026-10-20 09:42:08.422883+00', '2026-10-20 09:42:08.422883+00', '00000000-0000-0000-0000-000000000001');


--
-- Data for Name: idempotency_keys; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_deliveries; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_preferences; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_receipts; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_rules; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_source_runs; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: notification_targets; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('739fd24e-9d7b-4289-ac44-2f99366e4878', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('6496b477-164e-4993-8da3-40ebf18f46d4', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('f68bd0fe-b4f5-48a0-99e9-1fea56fe42b2', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('afc1d7f6-44ff-46b8-9f13-bf14b795dcca', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('9bd9758e-193f-45dd-a547-18d168da3196', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('5ac0896b-b840-48c0-8554-196044d5def6', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('18b1c69d-6650-4543-a8d8-4baca86c4aea', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('69b0014e-b58e-4ce7-a13f-db9df30b6db9', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('03d43d05-2404-4817-9319-dd3b135f973b', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('3d118623-db26-4914-93d6-741ec21b3a0d', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('91f11e29-8465-42d4-ae44-dde57b694456', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('8252cfc6-a35e-4b70-a8c1-3ed8f414c508', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('332c4d0f-cdcc-47c7-a3be-159b21198ea0', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('352f0f23-a6d5-404b-aff5-9133731d91d2', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('e5b115a3-c980-4b76-929d-82e543ae2415', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('70a9d87c-5bbe-4cd4-8295-133cc8abce52', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('109c900c-2347-4aa2-8fb9-de5e1819e775', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('95b24b01-bb8c-4e21-88e5-86bb656f0acc', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('115912ad-3ff1-4bf2-b6ae-6378e19cfa97', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('585873b5-4332-4f32-9b26-75e0ead3c150', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('a4ca248a-9d03-4c37-b356-3f73bc6936dd', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('abb3dff0-2ef4-41a3-aa34-550efb454b76', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('0b28b7cf-f175-4d3d-9ed2-070e916613a7', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('b4b91b7e-b684-48c7-9933-ef4a3fec9882', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('431cf406-bd19-402a-a62a-57a6785f52ac', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('91338ce2-e1e1-4e0d-9b81-dddd959e810a', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('5ff6121e-4af3-4d12-b0b6-28ac29db8a2d', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('41d28c3a-5f3f-4cf0-9e1f-bae553e1fe34', 'ALL', NULL, NULL, NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('73dcdbd5-df15-4592-b6f7-29515f0450b5', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('ab4bf66d-2d50-44a3-b53a-26f78593697e', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('ff4dafb0-e2f2-4ff0-9f89-8dacb28b5138', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('8b1bc313-ee68-4b13-b33b-e7e8ae6b94cc', 'ROLE', NULL, 'REVISOR', NULL);
INSERT INTO "wasichai"."notification_targets" ("notification_id", "type", "user_id", "role_name", "unit_id") VALUES ('835d28d0-c495-4571-8c0a-4fa88cf3dd76', 'ROLE', NULL, 'REVISOR', NULL);


--
-- Data for Name: notifications; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('739fd24e-9d7b-4289-ac44-2f99366e4878', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0001 pendiente de revisión', 'Bodega Mamani 1 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "3696eeb1-3a66-4717-a255-0bdd6feac753"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:11.722416+00', NULL, NULL, 'automation:avisar-revision', '3696eeb1-3a66-4717-a255-0bdd6feac753.0', '91404799cf190e22d4d874011ec1e90b8da9bb5bb1d7909fded63d89a2972a00', NULL, NULL, '2026-10-20 09:42:11.722416+00', '2026-10-20 09:42:11.722416+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('6496b477-164e-4993-8da3-40ebf18f46d4', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0009 pendiente de revisión', 'Estudio Quispe 9 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "b24a4af3-3f66-42f3-8105-40671715bd89"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:12.688011+00', NULL, NULL, 'automation:avisar-revision', 'b24a4af3-3f66-42f3-8105-40671715bd89.0', 'ca701c05e7043086b1c416dbf520eb0543c4745ba15f9d8c27e22d5555d71674', NULL, NULL, '2026-10-20 09:42:12.688011+00', '2026-10-20 09:42:12.688011+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('f68bd0fe-b4f5-48a0-99e9-1fea56fe42b2', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0010 pendiente de revisión', 'Taller Flores 10 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "24e2a46b-913c-4e70-b075-17229a78f771"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:12.725596+00', NULL, NULL, 'automation:avisar-revision', '24e2a46b-913c-4e70-b075-17229a78f771.0', '65f6efdf0f491c441ccfa72ac9c2ca00b3e4a4c11a4c300af3b3434ea5f16364', NULL, NULL, '2026-10-20 09:42:12.725596+00', '2026-10-20 09:42:12.725596+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('afc1d7f6-44ff-46b8-9f13-bf14b795dcca', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0007 pendiente de revisión', 'Taller Rojas 7 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "e93a1aa3-9d89-4066-a7b8-201cd9b20a51"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:12.764347+00', NULL, NULL, 'automation:avisar-revision', 'e93a1aa3-9d89-4066-a7b8-201cd9b20a51.0', '0ce9cb22061c3acb7ce418365a6a459e9508c5e5a1ea35b2b77617df38c38a3b', NULL, NULL, '2026-10-20 09:42:12.764347+00', '2026-10-20 09:42:12.764347+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('9bd9758e-193f-45dd-a547-18d168da3196', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0004 pendiente de revisión', 'Taller Castillo 4 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "ffbe8e6d-acf1-4651-a521-9a013f7a0b6b"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:12.799115+00', NULL, NULL, 'automation:avisar-revision', 'ffbe8e6d-acf1-4651-a521-9a013f7a0b6b.0', 'd9bb19e9774d78f70d42aeb22bb2807bf3a33deecc5bbe11386ce407d705eed8', NULL, NULL, '2026-10-20 09:42:12.799115+00', '2026-10-20 09:42:12.799115+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('5ac0896b-b840-48c0-8554-196044d5def6', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0002 pendiente de revisión', 'Bodega Vargas 2 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "59155d81-0f56-46b2-a04a-b32ec7fe7997"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:12.841307+00', NULL, NULL, 'automation:avisar-revision', '59155d81-0f56-46b2-a04a-b32ec7fe7997.0', 'df98d228b198b40bbed6c9e8561f38251f1bcf99b069f6aaa186897f8cd9dd8b', NULL, NULL, '2026-10-20 09:42:12.841307+00', '2026-10-20 09:42:12.841307+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('18b1c69d-6650-4543-a8d8-4baca86c4aea', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0008 pendiente de revisión', 'Estudio Castillo 8 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "f1fab9f1-f034-4ded-be41-42f93b80f817"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.052224+00', NULL, NULL, 'automation:avisar-revision', 'f1fab9f1-f034-4ded-be41-42f93b80f817.0', '7c86b9256d64bb0428d83b398f8650f67959862697231926fd6ed81ea37704b6', NULL, NULL, '2026-10-20 09:42:13.052224+00', '2026-10-20 09:42:13.052224+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('69b0014e-b58e-4ce7-a13f-db9df30b6db9', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0011 pendiente de revisión', 'Estudio Mamani 11 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "fbf8eaee-e14d-4843-9d09-b470a9653f92"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.16773+00', NULL, NULL, 'automation:avisar-revision', 'fbf8eaee-e14d-4843-9d09-b470a9653f92.0', '4c2387d3c9726d8d665edada6782d02a53917722c313335c946beb7956c391e7', NULL, NULL, '2026-10-20 09:42:13.16773+00', '2026-10-20 09:42:13.16773+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('03d43d05-2404-4817-9319-dd3b135f973b', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0003 pendiente de revisión', 'Estudio Chávez 3 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "5f31a5d3-a189-4023-933f-7f1df56de85c"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.32002+00', NULL, NULL, 'automation:avisar-revision', '5f31a5d3-a189-4023-933f-7f1df56de85c.0', '43c6925df52f2b587d2918b039756f428f56f5cbb21fb60b299ef405895baf0c', NULL, NULL, '2026-10-20 09:42:13.32002+00', '2026-10-20 09:42:13.32002+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('3d118623-db26-4914-93d6-741ec21b3a0d', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0006 pendiente de revisión', 'Restaurante Vargas 6 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "ecbe4879-eca3-4cf4-8f2f-25224eab99bd"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.344528+00', NULL, NULL, 'automation:avisar-revision', 'ecbe4879-eca3-4cf4-8f2f-25224eab99bd.0', '7093df662395ca5d438fe3b4e24b8718eb600db44002c78a074d82c580e4cb4c', NULL, NULL, '2026-10-20 09:42:13.344528+00', '2026-10-20 09:42:13.344528+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('91f11e29-8465-42d4-ae44-dde57b694456', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0016 pendiente de revisión', 'Taller Castillo 16 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "d16e695d-720a-4744-91af-aeb575bedce2"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.692414+00', NULL, NULL, 'automation:avisar-revision', 'd16e695d-720a-4744-91af-aeb575bedce2.0', '3aed20e64181104d94d9e19f89bb20a3759dd8703eb94ad78b4809f786551112', NULL, NULL, '2026-10-20 09:42:13.692414+00', '2026-10-20 09:42:13.692414+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('8252cfc6-a35e-4b70-a8c1-3ed8f414c508', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0013 pendiente de revisión', 'Botica Vargas 13 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "f65ac4de-c341-49f3-88ba-cf3a2265a374"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.722873+00', NULL, NULL, 'automation:avisar-revision', 'f65ac4de-c341-49f3-88ba-cf3a2265a374.0', 'e19c14b74f7c1135a135dc171d7c678cc3ce8606fadbdde3b90c596d03cfb0a8', NULL, NULL, '2026-10-20 09:42:13.722873+00', '2026-10-20 09:42:13.722873+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('332c4d0f-cdcc-47c7-a3be-159b21198ea0', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0018 pendiente de revisión', 'Estudio Torres 18 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "9d556da0-f59b-4a79-a728-bd63536ddc45"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.812721+00', NULL, NULL, 'automation:avisar-revision', '9d556da0-f59b-4a79-a728-bd63536ddc45.0', '1f449eaad6b6d233bcdb02913c28f1de69b94ce7ed0fbc52907700d27d78b6c7', NULL, NULL, '2026-10-20 09:42:13.812721+00', '2026-10-20 09:42:13.812721+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('352f0f23-a6d5-404b-aff5-9133731d91d2', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0015 pendiente de revisión', 'Restaurante Torres 15 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "98778346-7f16-4252-be16-6781cfd7b6a2"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.843576+00', NULL, NULL, 'automation:avisar-revision', '98778346-7f16-4252-be16-6781cfd7b6a2.0', '402ddce2c033aca050f7544add1b28e517c7cd771ea1d9253b1a8993f96849c1', NULL, NULL, '2026-10-20 09:42:13.843576+00', '2026-10-20 09:42:13.843576+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('e5b115a3-c980-4b76-929d-82e543ae2415', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0014 pendiente de revisión', 'Taller Castillo 14 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "9263f1a8-12a3-46aa-8f04-a1505239fa45"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.872831+00', NULL, NULL, 'automation:avisar-revision', '9263f1a8-12a3-46aa-8f04-a1505239fa45.0', '291269d3e527597ebd4172c8ed7f83253aa019dbc3005d27338a587a825ae049', NULL, NULL, '2026-10-20 09:42:13.872831+00', '2026-10-20 09:42:13.872831+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('70a9d87c-5bbe-4cd4-8295-133cc8abce52', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0020 pendiente de revisión', 'Estudio Medina 20 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "5af20f44-aa18-476c-bf07-1b844de0b892"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.906444+00', NULL, NULL, 'automation:avisar-revision', '5af20f44-aa18-476c-bf07-1b844de0b892.0', 'a5a14fb66efe5c2e835a395921208375557e7ee397f63d261124fe3b8d714553', NULL, NULL, '2026-10-20 09:42:13.906444+00', '2026-10-20 09:42:13.906444+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('109c900c-2347-4aa2-8fb9-de5e1819e775', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0017 pendiente de revisión', 'Taller Ramos 17 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "1cfb238e-0268-4a9b-91e4-cf84758797f1"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:13.962707+00', NULL, NULL, 'automation:avisar-revision', '1cfb238e-0268-4a9b-91e4-cf84758797f1.0', '0881c9d1fdd0d24ad5d6c2db9fd501852b9f5e88a03634b73096d890901c0b35', NULL, NULL, '2026-10-20 09:42:13.962707+00', '2026-10-20 09:42:13.962707+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('95b24b01-bb8c-4e21-88e5-86bb656f0acc', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0023 pendiente de revisión', 'Botica Salazar 23 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "6db3ac47-a69d-472f-8b31-50476b8fe484"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.663632+00', NULL, NULL, 'automation:avisar-revision', '6db3ac47-a69d-472f-8b31-50476b8fe484.0', '22250b18a38d57b26eab5fb8f29e7809fc10fe632f1b88e750b754e48e439665', NULL, NULL, '2026-10-20 09:42:14.663632+00', '2026-10-20 09:42:14.663632+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('115912ad-3ff1-4bf2-b6ae-6378e19cfa97', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0027 pendiente de revisión', 'Botica Chávez 27 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "54954b2b-23a1-421e-a341-21618051b2a9"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.681991+00', NULL, NULL, 'automation:avisar-revision', '54954b2b-23a1-421e-a341-21618051b2a9.0', 'f673b1651868bb112f4782c5ca6d62e76c0cab3a03430168e4365972172fd917', NULL, NULL, '2026-10-20 09:42:14.681991+00', '2026-10-20 09:42:14.681991+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('585873b5-4332-4f32-9b26-75e0ead3c150', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0029 pendiente de revisión', 'Restaurante Cáceres 29 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "a0da9137-f268-4f34-965e-2fa6fe299f64"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.700601+00', NULL, NULL, 'automation:avisar-revision', 'a0da9137-f268-4f34-965e-2fa6fe299f64.0', 'b30acdd352bb430d0542d2f650c2d5c36984bc9e8d47ed4429fff5e62dfcad39', NULL, NULL, '2026-10-20 09:42:14.700601+00', '2026-10-20 09:42:14.700601+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('abb3dff0-2ef4-41a3-aa34-550efb454b76', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0022 pendiente de revisión', 'Restaurante Ramos 22 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "9b70de67-dde6-4eff-8940-f5665ebf8529"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.814357+00', NULL, NULL, 'automation:avisar-revision', '9b70de67-dde6-4eff-8940-f5665ebf8529.0', 'ebca74d5790fb8916f8f26a42e238216d4e1208ea6d703db79ed3995f5637ba6', NULL, NULL, '2026-10-20 09:42:14.814357+00', '2026-10-20 09:42:14.814357+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('5ff6121e-4af3-4d12-b0b6-28ac29db8a2d', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0036 pendiente de revisión', 'Estudio Vargas 36 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "08afe0b4-153d-42fd-9aa7-ee1d572e71f4"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.673391+00', NULL, NULL, 'automation:avisar-revision', '08afe0b4-153d-42fd-9aa7-ee1d572e71f4.0', '279179aa9b370419b1cca93c0db1a8d951c2d7f0aec3d32a3e1a0e900ee261c9', NULL, NULL, '2026-10-20 09:42:15.673391+00', '2026-10-20 09:42:15.673391+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('41d28c3a-5f3f-4cf0-9e1f-bae553e1fe34', '00000000-0000-0000-0000-000000000001', 'INFO', 'Horario de atención de la Subgerencia de Licencias', 'Atención al público de lunes a viernes, de 8:30 a 16:30.', NULL, NULL, '2026-10-20 09:42:15.69097+00', NULL, NULL, 'manual', NULL, '25fb5195f4571a86027dcf9b206e45522a737f7c868f664cbf1e05eab134507f', NULL, '00000000-0000-0000-0000-000000000100', '2026-10-20 09:42:15.69097+00', '2026-10-20 09:42:15.69097+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('ab4bf66d-2d50-44a3-b53a-26f78593697e', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0034 pendiente de revisión', 'Ferretería Vargas 34 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "df7c462b-84f2-4283-899f-78e434e54c87"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.713739+00', NULL, NULL, 'automation:avisar-revision', 'df7c462b-84f2-4283-899f-78e434e54c87.0', 'b6ef1515aa0e03ce2d59f4e27d4ede376e79cd1dfe15827ee6aedafa2214e28a', NULL, NULL, '2026-10-20 09:42:15.713739+00', '2026-10-20 09:42:15.713739+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('ff4dafb0-e2f2-4ff0-9f89-8dacb28b5138', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0037 pendiente de revisión', 'Estudio Ramos 37 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "0e114cf0-753a-4be5-ba62-0ff1ede9709a"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.742271+00', NULL, NULL, 'automation:avisar-revision', '0e114cf0-753a-4be5-ba62-0ff1ede9709a.0', '73f130bc6238e43f076c86ec70d7a7682f2ac9acb55a66e9f8c0405949c2e57a', NULL, NULL, '2026-10-20 09:42:15.742271+00', '2026-10-20 09:42:15.742271+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('8b1bc313-ee68-4b13-b33b-e7e8ae6b94cc', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0032 pendiente de revisión', 'Botica Vargas 32 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "c9ef1c08-1768-4d35-8409-377d6225deb7"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.774903+00', NULL, NULL, 'automation:avisar-revision', 'c9ef1c08-1768-4d35-8409-377d6225deb7.0', '05d4d4961072818ef4131e857e8ab928130118ec012ddd1e5c7d17c3393019a3', NULL, NULL, '2026-10-20 09:42:15.774903+00', '2026-10-20 09:42:15.774903+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('835d28d0-c495-4571-8c0a-4fa88cf3dd76', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0035 pendiente de revisión', 'Taller Salazar 35 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "3adf714a-ee09-4472-be0a-23ce2ce93250"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.793897+00', NULL, NULL, 'automation:avisar-revision', '3adf714a-ee09-4472-be0a-23ce2ce93250.0', 'a3f5a770d7ae178fc5022de3ad5c9e2f3634c1c1d96152c1bdd5056c37ae1475', NULL, NULL, '2026-10-20 09:42:15.793897+00', '2026-10-20 09:42:15.793897+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('a4ca248a-9d03-4c37-b356-3f73bc6936dd', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0025 pendiente de revisión', 'Botica Medina 25 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "817ac2ac-ff28-4b1f-bc10-b4381ea89a16"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.752968+00', NULL, NULL, 'automation:avisar-revision', '817ac2ac-ff28-4b1f-bc10-b4381ea89a16.0', 'f726d54624cf0757f1c9fe4d4a7a823eb43fa856b037c640b5ec34ad4baf4206', NULL, NULL, '2026-10-20 09:42:14.752968+00', '2026-10-20 09:42:14.752968+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('0b28b7cf-f175-4d3d-9ed2-070e916613a7', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0021 pendiente de revisión', 'Taller Chávez 21 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "52b50a86-29eb-4ac6-8947-776ddd57bb8b"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.835604+00', NULL, NULL, 'automation:avisar-revision', '52b50a86-29eb-4ac6-8947-776ddd57bb8b.0', '2047f1303b02265aedc906216c1a80fb069b1f2fe6bb292a50fc9fecad698de8', NULL, NULL, '2026-10-20 09:42:14.835604+00', '2026-10-20 09:42:14.835604+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('b4b91b7e-b684-48c7-9933-ef4a3fec9882', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0024 pendiente de revisión', 'Restaurante Rojas 24 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "1044e268-2c76-4923-8bab-cbeccad576c6"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.86386+00', NULL, NULL, 'automation:avisar-revision', '1044e268-2c76-4923-8bab-cbeccad576c6.0', 'd268fcdc75b6a22adacf5d7099b8182f0a85bd0c2246c0c7dadfbee70f892b6f', NULL, NULL, '2026-10-20 09:42:14.86386+00', '2026-10-20 09:42:14.86386+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('431cf406-bd19-402a-a62a-57a6785f52ac', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0030 pendiente de revisión', 'Restaurante Ramos 30 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "bdb53c41-7a9c-4101-8eac-4af6c1a643ba"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.896479+00', NULL, NULL, 'automation:avisar-revision', 'bdb53c41-7a9c-4101-8eac-4af6c1a643ba.0', 'd30b6ebff7103c80cbe8a9aa2c591eb4f5a4eaac650683fbffcf76f7858c327a', NULL, NULL, '2026-10-20 09:42:14.896479+00', '2026-10-20 09:42:14.896479+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('91338ce2-e1e1-4e0d-9b81-dddd959e810a', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0028 pendiente de revisión', 'Estudio Paredes 28 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "eba4a091-eca0-43b9-b20a-71e7379c9157"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:14.919971+00', NULL, NULL, 'automation:avisar-revision', 'eba4a091-eca0-43b9-b20a-71e7379c9157.0', '573ac77b434e06430adfc271463e0598e340212ec7837997f70893b2e2ffa66b', NULL, NULL, '2026-10-20 09:42:14.919971+00', '2026-10-20 09:42:14.919971+00');
INSERT INTO "wasichai"."notifications" ("id", "organization_id", "kind", "title", "body", "link", "link_object_id", "publish_at", "expires_at", "due_at", "source", "source_key", "fingerprint", "resolved_at", "created_by", "created_at", "updated_at") VALUES ('73dcdbd5-df15-4592-b6f7-29515f0450b5', '00000000-0000-0000-0000-000000000001', 'INFO', 'Licencia LF-2026-0039 pendiente de revisión', 'Ferretería Mamani 39 espera revisión técnica.', '{"type": "RECORD", "object": "licencia", "recordId": "829736de-17e2-4393-8925-eff86e054979"}', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', '2026-10-20 09:42:15.69579+00', NULL, NULL, 'automation:avisar-revision', '829736de-17e2-4393-8925-eff86e054979.0', '8ca2682a724ae3510fbf8f41da90e45336afb1a8596e7a1ee7a2d969becd6cfe', NULL, NULL, '2026-10-20 09:42:15.69579+00', '2026-10-20 09:42:15.69579+00');


--
-- Data for Name: object_actions; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: org_units; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."org_units" ("id", "organization_id", "parent_id", "code", "label", "created_at", "updated_at") VALUES ('6cc217f3-7fbc-492d-a7d0-bd30007fcd56', '00000000-0000-0000-0000-000000000001', NULL, 'GDE', 'Gerencia de Desarrollo Económico', '2026-10-20 09:42:06.344221+00', '2026-10-20 09:42:06.344221+00');
INSERT INTO "wasichai"."org_units" ("id", "organization_id", "parent_id", "code", "label", "created_at", "updated_at") VALUES ('0865612c-a187-4ffc-be93-9978ade56ecd', '00000000-0000-0000-0000-000000000001', '6cc217f3-7fbc-492d-a7d0-bd30007fcd56', 'SGLC', 'Subgerencia de Licencias Comerciales', '2026-10-20 09:42:06.393204+00', '2026-10-20 09:42:06.393204+00');


--
-- Data for Name: organizations; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."organizations" ("id", "name", "slug", "created_at", "updated_at") VALUES ('00000000-0000-0000-0000-000000000001', 'Demo', 'demo', '2026-10-20 09:41:57.341997+00', '2026-10-20 09:41:57.341997+00');


--
-- Data for Name: pages; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."pages" ("id", "object_id", "name", "label", "definition", "created_at", "updated_at", "organization_id", "kind", "template") VALUES ('37e4abb4-2c5d-4a91-a767-e3c75f322669', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'licencia-detalle', 'Licencia', '{"page": {"url": null, "form": null, "type": "PAGE", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [{"url": null, "form": null, "type": "REGION", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": "MAIN", "target": null, "content": null, "visible": null, "children": [{"url": null, "form": null, "type": "TABS", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [{"key": "SOLICITUD", "url": null, "form": null, "type": "TAB", "field": null, "style": null, "title": "Solicitud", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [{"url": null, "form": "solicitud", "type": "FORM", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}, {"key": "RELACIONES", "url": null, "form": null, "type": "TAB", "field": null, "style": null, "title": "Predio y solicitante", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [{"url": null, "form": null, "type": "RELATED_LIST", "field": null, "style": null, "title": "Predio", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": "licencia_predio"}, {"url": null, "form": null, "type": "RELATED_LIST", "field": null, "style": null, "title": "Solicitante", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": "licencia_solicitante"}], "editable": null, "geometry": null, "transition": null, "relationship": null}, {"key": "HISTORIAL", "url": null, "form": null, "type": "TAB", "field": null, "style": null, "title": "Historial", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [{"url": null, "form": null, "type": "HISTORY", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}, {"url": null, "form": null, "type": "REGION", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": "RIGHT", "target": null, "content": null, "visible": null, "children": [{"url": null, "form": null, "type": "WORKFLOW", "field": null, "style": null, "title": "Estado del trámite", "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": null, "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": null}, {"url": null, "form": null, "type": "TEXT", "field": null, "style": null, "title": null, "action": null, "column": 1, "fields": null, "layout": "single-column", "region": null, "target": null, "content": "Toda modificación de una licencia exige indicar el motivo, que queda registrado en el historial.", "visible": null, "children": [], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}], "editable": null, "geometry": null, "transition": null, "relationship": null}}', '2026-10-20 09:42:08.540452+00', '2026-10-20 09:42:08.540452+00', '00000000-0000-0000-0000-000000000001', 'RECORD_DETAIL', 'main-and-right-sidebar');


--
-- Data for Name: permissions; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('e5b3b569-01fe-482b-b96e-0992e9656788', '00000000-0000-0000-0000-000000000010', NULL, 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('8c89f19e-480b-42fa-8ac3-5b7ecd9490fd', '00000000-0000-0000-0000-000000000010', NULL, 'CREATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('8f0423c1-a5b7-446d-a2c4-ddbb97ea705f', '00000000-0000-0000-0000-000000000010', NULL, 'UPDATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('157a2270-a38d-4ec5-939f-3daddb24e1c8', '00000000-0000-0000-0000-000000000010', NULL, 'DELETE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('1da9efeb-6843-4c56-9cfc-e4caee3df769', '00000000-0000-0000-0000-000000000010', NULL, 'MANAGE_METADATA', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('aef59578-2c22-4276-8ded-d88673dd19d6', '00000000-0000-0000-0000-000000000010', NULL, 'MANAGE_ORGANIZATION', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('9126b5fa-7ae6-47e8-869e-83c2014cef41', '0568856d-528e-4c63-9e08-ce8c5c8c4505', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('fc2dda43-ae55-48b9-9a6c-9e366c5797ff', '0568856d-528e-4c63-9e08-ce8c5c8c4505', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'CREATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('6fd87cd6-594a-4245-a4c2-72264e25cea9', '0568856d-528e-4c63-9e08-ce8c5c8c4505', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'UPDATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('0bb82cde-0fee-46d3-9ad0-0a290f0b6f74', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('7464b704-5637-4e3c-8c28-45be85dde764', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'CREATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('dc64d23d-73e5-47a7-9a6b-18985f30304d', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'UPDATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('1455f37a-7fef-4c43-b844-45b39fff1d76', '0568856d-528e-4c63-9e08-ce8c5c8c4505', 'd4333a27-6895-4ced-8cca-808a5c439298', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('3c199936-6fc9-455f-8d4c-7ad0b549890b', 'd5d5575b-017f-451d-8b7b-cb958619e12a', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('4819ca7b-b7b8-4d9a-aef5-0218701159d6', 'd5d5575b-017f-451d-8b7b-cb958619e12a', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'UPDATE', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('9234cc8f-fc56-43d7-8cbd-861838bb658d', 'd5d5575b-017f-451d-8b7b-cb958619e12a', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('15220e46-90de-419e-8665-519b953998aa', 'd5d5575b-017f-451d-8b7b-cb958619e12a', 'd4333a27-6895-4ced-8cca-808a5c439298', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('59baf9c2-094f-4f37-95ca-2f24c2964b91', '1cbd090a-8508-442a-9c20-3d53077e7050', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('66ea7360-a826-4219-a6a0-97185fd32c19', '1cbd090a-8508-442a-9c20-3d53077e7050', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'READ', true);
INSERT INTO "wasichai"."permissions" ("id", "role_id", "object_id", "action", "allowed") VALUES ('b586cdcb-fa0b-4fe4-a3c5-01abd36d677a', '1cbd090a-8508-442a-9c20-3d53077e7050', 'd4333a27-6895-4ced-8cca-808a5c439298', 'READ', true);


--
-- Data for Name: relationships; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."relationships" ("id", "organization_id", "name", "label", "type", "source_object_id", "target_object_id", "relation_field_id", "join_table", "created_at", "inverse_label") VALUES ('2b4ea0ba-a794-4fd9-8606-e7106c1133e6', '00000000-0000-0000-0000-000000000001', 'predio_titular', 'Titular', 'MANY_TO_ONE', 'd4333a27-6895-4ced-8cca-808a5c439298', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', '9c432291-a1d9-4a3e-a24e-946b0efdd722', NULL, '2026-10-20 09:42:05.375933+00', 'Predios');
INSERT INTO "wasichai"."relationships" ("id", "organization_id", "name", "label", "type", "source_object_id", "target_object_id", "relation_field_id", "join_table", "created_at", "inverse_label") VALUES ('8cdf9279-e7fa-4d49-82b7-f7685c466fc0', '00000000-0000-0000-0000-000000000001', 'licencia_solicitante', 'Solicitante', 'MANY_TO_ONE', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'e536b189-e9cf-4aa6-ac47-b7dbfa79d182', 'fad15abe-f0e2-485c-bb38-ecf71af1787a', NULL, '2026-10-20 09:42:05.512014+00', 'Licencias');
INSERT INTO "wasichai"."relationships" ("id", "organization_id", "name", "label", "type", "source_object_id", "target_object_id", "relation_field_id", "join_table", "created_at", "inverse_label") VALUES ('adde0827-6b2a-4f42-b08c-096689c8ffd5', '00000000-0000-0000-0000-000000000001', 'licencia_predio', 'Predio', 'MANY_TO_ONE', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'd4333a27-6895-4ced-8cca-808a5c439298', 'b57dfdee-1cc8-4598-a526-f9250f043f21', NULL, '2026-10-20 09:42:05.642511+00', 'Licencias');


--
-- Data for Name: roles; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."roles" ("id", "organization_id", "name", "label", "created_at", "own_records_only") VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000001', 'ADMIN', 'Administrator', '2026-10-20 09:41:57.341997+00', false);
INSERT INTO "wasichai"."roles" ("id", "organization_id", "name", "label", "created_at", "own_records_only") VALUES ('0568856d-528e-4c63-9e08-ce8c5c8c4505', '00000000-0000-0000-0000-000000000001', 'REGISTRADOR', 'Registrador de licencias', '2026-10-20 09:42:05.837591+00', false);
INSERT INTO "wasichai"."roles" ("id", "organization_id", "name", "label", "created_at", "own_records_only") VALUES ('d5d5575b-017f-451d-8b7b-cb958619e12a', '00000000-0000-0000-0000-000000000001', 'REVISOR', 'Revisor técnico', '2026-10-20 09:42:06.012442+00', false);
INSERT INTO "wasichai"."roles" ("id", "organization_id", "name", "label", "created_at", "own_records_only") VALUES ('1cbd090a-8508-442a-9c20-3d53077e7050', '00000000-0000-0000-0000-000000000001', 'CONSULTA', 'Consulta', '2026-10-20 09:42:06.101807+00', false);


--
-- Data for Name: service_accounts; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."service_accounts" ("id", "organization_id", "name", "secret_hash", "enabled", "created_at", "secret_rotated_at") VALUES ('04046ce4-125c-4bde-a60b-2609fbfcae39', '00000000-0000-0000-0000-000000000001', 'integracion-rentas', '$2a$10$WmEP3/vovZAe/W.F7/KCR.qIAnJQ5z9Outup2lQC1.pmIqz8DB1um', true, '2026-10-20 09:42:07.082344+00', '2026-10-20 09:42:07.082344+00');


--
-- Data for Name: stored_files; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: user_org_units; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."user_org_units" ("user_id", "unit_id") VALUES ('b2556764-05cc-416e-af0e-ec0313b72e5c', '0865612c-a187-4ffc-be93-9978ade56ecd');
INSERT INTO "wasichai"."user_org_units" ("user_id", "unit_id") VALUES ('5a574dae-3936-415d-a3fd-3fcabee18411', '0865612c-a187-4ffc-be93-9978ade56ecd');
INSERT INTO "wasichai"."user_org_units" ("user_id", "unit_id") VALUES ('1c2668cf-c06f-4855-8ea9-8454ee0272cc', '6cc217f3-7fbc-492d-a7d0-bd30007fcd56');


--
-- Data for Name: user_preferences; Type: TABLE DATA; Schema: wasichai; Owner: -
--



--
-- Data for Name: user_roles; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."user_roles" ("user_id", "role_id") VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000010');
INSERT INTO "wasichai"."user_roles" ("user_id", "role_id") VALUES ('b2556764-05cc-416e-af0e-ec0313b72e5c', '0568856d-528e-4c63-9e08-ce8c5c8c4505');
INSERT INTO "wasichai"."user_roles" ("user_id", "role_id") VALUES ('5a574dae-3936-415d-a3fd-3fcabee18411', 'd5d5575b-017f-451d-8b7b-cb958619e12a');
INSERT INTO "wasichai"."user_roles" ("user_id", "role_id") VALUES ('1c2668cf-c06f-4855-8ea9-8454ee0272cc', '1cbd090a-8508-442a-9c20-3d53077e7050');
INSERT INTO "wasichai"."user_roles" ("user_id", "role_id") VALUES ('04046ce4-125c-4bde-a60b-2609fbfcae39', '1cbd090a-8508-442a-9c20-3d53077e7050');


--
-- Data for Name: users; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."users" ("id", "organization_id", "email", "password_hash", "display_name", "enabled", "created_at", "updated_at", "tokens_valid_after") VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000001', 'admin@wasichai.local', '$2a$10$vZ1/D.ORhnHCBpfdi2qcneeT28E8V42tnXIQRs74ubzXzE4sPWMPa', 'Platform Administrator', true, '2026-10-20 09:41:57.341997+00', '2026-10-20 09:41:57.341997+00', NULL);
INSERT INTO "wasichai"."users" ("id", "organization_id", "email", "password_hash", "display_name", "enabled", "created_at", "updated_at", "tokens_valid_after") VALUES ('b2556764-05cc-416e-af0e-ec0313b72e5c', '00000000-0000-0000-0000-000000000001', 'registrador@demo.example', '$2a$10$DF9WEkjHikMt2RX.bl1I4O/GHhuWlruwDiT6y9dTeP6O4MqKeuUCa', 'Rosa Registradora', true, '2026-10-20 09:42:06.51018+00', '2026-10-20 09:42:06.51018+00', NULL);
INSERT INTO "wasichai"."users" ("id", "organization_id", "email", "password_hash", "display_name", "enabled", "created_at", "updated_at", "tokens_valid_after") VALUES ('5a574dae-3936-415d-a3fd-3fcabee18411', '00000000-0000-0000-0000-000000000001', 'revisor@demo.example', '$2a$10$.gLFvXXFB9WvSY8vAWTdcOb64eiHyZuTXLYY6DDmez/pDoC4.Wdg6', 'Raúl Revisor', true, '2026-10-20 09:42:06.704659+00', '2026-10-20 09:42:06.704659+00', NULL);
INSERT INTO "wasichai"."users" ("id", "organization_id", "email", "password_hash", "display_name", "enabled", "created_at", "updated_at", "tokens_valid_after") VALUES ('1c2668cf-c06f-4855-8ea9-8454ee0272cc', '00000000-0000-0000-0000-000000000001', 'consulta@demo.example', '$2a$10$Zlg8PQ1XYI2G9umbaOaFmui/Mvo3kjA4uw9huyu8T0ccf8rZMZpY6', 'Carmen Consulta', true, '2026-10-20 09:42:06.861794+00', '2026-10-20 09:42:06.861794+00', NULL);
INSERT INTO "wasichai"."users" ("id", "organization_id", "email", "password_hash", "display_name", "enabled", "created_at", "updated_at", "tokens_valid_after") VALUES ('04046ce4-125c-4bde-a60b-2609fbfcae39', '00000000-0000-0000-0000-000000000001', '04046ce4-125c-4bde-a60b-2609fbfcae39@service-accounts.invalid', '$2a$10$tartjd/leUxdddXteh3JWOxXPkexrVa9J88QtsYF0hO5xge/yWAlu', 'integracion-rentas', false, '2026-10-20 09:42:07.082344+00', '2026-10-20 09:42:07.082344+00', NULL);


--
-- Data for Name: views; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."views" ("id", "object_id", "name", "label", "definition", "created_at", "updated_at", "organization_id", "is_default") VALUES ('6f3588ab-9153-479a-8918-3cb6268ad8e4', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'bandeja', 'Bandeja de licencias', '{"sort": {"field": "codigo", "direction": "ASC"}, "columns": ["codigo", "razon_social", "giro", "area_local", "fecha_solicitud", "resultado"], "filters": {}, "pageSize": 25}', '2026-10-20 09:42:08.210836+00', '2026-10-20 09:42:08.210836+00', '00000000-0000-0000-0000-000000000001', true);
INSERT INTO "wasichai"."views" ("id", "object_id", "name", "label", "definition", "created_at", "updated_at", "organization_id", "is_default") VALUES ('8c596df8-6813-4fa8-a0a5-febb792ef45d', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'restaurantes', 'Restaurantes', '{"sort": {"field": "fecha_solicitud", "direction": "DESC"}, "columns": ["codigo", "razon_social", "area_local", "fecha_solicitud"], "filters": {"giro": "RESTAURANTE"}, "pageSize": 25}', '2026-10-20 09:42:08.282192+00', '2026-10-20 09:42:08.282192+00', '00000000-0000-0000-0000-000000000001', false);
INSERT INTO "wasichai"."views" ("id", "object_id", "name", "label", "definition", "created_at", "updated_at", "organization_id", "is_default") VALUES ('ab57c133-d48c-4c3f-9dd4-0468b43df1a6', 'd4333a27-6895-4ced-8cca-808a5c439298', 'comerciales', 'Predios comerciales', '{"sort": {"field": "codigo", "direction": "ASC"}, "columns": ["codigo", "direccion", "area", "valor_autovaluo"], "filters": {"uso": "COMERCIAL"}, "pageSize": 25}', '2026-10-20 09:42:08.335326+00', '2026-10-20 09:42:08.335326+00', '00000000-0000-0000-0000-000000000001', false);


--
-- Data for Name: workflows; Type: TABLE DATA; Schema: wasichai; Owner: -
--

INSERT INTO "wasichai"."workflows" ("id", "organization_id", "object_id", "name", "label", "enabled", "definition", "created_at", "updated_at") VALUES ('573420d1-a7c9-432f-9c76-82a37855c4cb', '00000000-0000-0000-0000-000000000001', '865abea1-bc63-4076-a7cd-f1b8cc4c1f86', 'licencia-tramite', 'Trámite de licencia', true, '{"states": [{"x": 40.0, "y": 160.0, "name": "borrador", "type": "INITIAL", "label": "Borrador"}, {"x": 320.0, "y": 160.0, "name": "en_revision", "type": "INTERMEDIATE", "label": "En revisión"}, {"x": 320.0, "y": 360.0, "name": "observada", "type": "INTERMEDIATE", "label": "Observada"}, {"x": 620.0, "y": 60.0, "name": "aprobada", "type": "FINAL", "label": "Aprobada"}, {"x": 620.0, "y": 260.0, "name": "rechazada", "type": "FINAL", "label": "Rechazada"}], "transitions": [{"to": "en_revision", "from": "borrador", "name": "enviar", "label": "Enviar a revisión", "roles": ["REGISTRADOR"]}, {"to": "observada", "from": "en_revision", "name": "observar", "label": "Observar", "roles": ["REVISOR"]}, {"to": "en_revision", "from": "observada", "name": "subsanar", "label": "Subsanar observaciones", "roles": ["REGISTRADOR"]}, {"to": "aprobada", "from": "en_revision", "name": "aprobar", "label": "Aprobar", "roles": ["REVISOR"]}, {"to": "rechazada", "from": "en_revision", "name": "rechazar", "label": "Rechazar", "roles": ["REVISOR"]}]}', '2026-10-20 09:42:07.391688+00', '2026-10-20 09:42:07.391688+00');


--
-- Name: contribuyente__00000000 contribuyente__00000000_codigo_key; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."contribuyente__00000000"
    ADD CONSTRAINT "contribuyente__00000000_codigo_key" UNIQUE ("codigo");


--
-- Name: contribuyente__00000000 contribuyente__00000000_pkey; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."contribuyente__00000000"
    ADD CONSTRAINT "contribuyente__00000000_pkey" PRIMARY KEY ("id");


--
-- Name: licencia__00000000 licencia__00000000_codigo_key; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."licencia__00000000"
    ADD CONSTRAINT "licencia__00000000_codigo_key" UNIQUE ("codigo");


--
-- Name: licencia__00000000 licencia__00000000_pkey; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."licencia__00000000"
    ADD CONSTRAINT "licencia__00000000_pkey" PRIMARY KEY ("id");


--
-- Name: predio__00000000 predio__00000000_codigo_key; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."predio__00000000"
    ADD CONSTRAINT "predio__00000000_codigo_key" UNIQUE ("codigo");


--
-- Name: predio__00000000 predio__00000000_pkey; Type: CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."predio__00000000"
    ADD CONSTRAINT "predio__00000000_pkey" PRIMARY KEY ("id");


--
-- Name: audit_log audit_log_correlation_id_valid; Type: CHECK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE "wasichai"."audit_log"
    ADD CONSTRAINT "audit_log_correlation_id_valid" CHECK (("correlation_id" ~ '^[A-Za-z0-9._-]{1,64}$'::"text")) NOT VALID;


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."audit_log"
    ADD CONSTRAINT "audit_log_pkey" PRIMARY KEY ("id");


--
-- Name: audit_log audit_log_source_valid; Type: CHECK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE "wasichai"."audit_log"
    ADD CONSTRAINT "audit_log_source_valid" CHECK (("source" ~ '^[A-Za-z0-9._:-]{1,64}$'::"text")) NOT VALID;


--
-- Name: automation_runs automation_runs_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automation_runs"
    ADD CONSTRAINT "automation_runs_pkey" PRIMARY KEY ("id");


--
-- Name: automations automations_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automations"
    ADD CONSTRAINT "automations_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: automations automations_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automations"
    ADD CONSTRAINT "automations_pkey" PRIMARY KEY ("id");


--
-- Name: custom_fields custom_fields_name_unique_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_fields"
    ADD CONSTRAINT "custom_fields_name_unique_per_object" UNIQUE ("object_id", "name");


--
-- Name: custom_fields custom_fields_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_fields"
    ADD CONSTRAINT "custom_fields_pkey" PRIMARY KEY ("id");


--
-- Name: custom_objects custom_objects_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_objects"
    ADD CONSTRAINT "custom_objects_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: custom_objects custom_objects_physical_table_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_objects"
    ADD CONSTRAINT "custom_objects_physical_table_unique" UNIQUE ("physical_table");


--
-- Name: custom_objects custom_objects_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_objects"
    ADD CONSTRAINT "custom_objects_pkey" PRIMARY KEY ("id");


--
-- Name: document_counters document_counters_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_counters"
    ADD CONSTRAINT "document_counters_pkey" PRIMARY KEY ("document_type_id", "year");


--
-- Name: document_types document_types_name_unique_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_types"
    ADD CONSTRAINT "document_types_name_unique_per_object" UNIQUE ("object_id", "name");


--
-- Name: document_types document_types_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_types"
    ADD CONSTRAINT "document_types_pkey" PRIMARY KEY ("id");


--
-- Name: document_types document_types_prefix_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_types"
    ADD CONSTRAINT "document_types_prefix_unique_per_org" UNIQUE ("organization_id", "prefix");


--
-- Name: documents documents_number_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_number_unique_per_org" UNIQUE ("organization_id", "number");


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_pkey" PRIMARY KEY ("id");


--
-- Name: field_permissions field_permissions_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."field_permissions"
    ADD CONSTRAINT "field_permissions_pkey" PRIMARY KEY ("role_id", "field_id");


--
-- Name: flyway_history_automation flyway_history_automation_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_automation"
    ADD CONSTRAINT "flyway_history_automation_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_core flyway_history_core_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_core"
    ADD CONSTRAINT "flyway_history_core_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_core_seed flyway_history_core_seed_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_core_seed"
    ADD CONSTRAINT "flyway_history_core_seed_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_documents flyway_history_documents_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_documents"
    ADD CONSTRAINT "flyway_history_documents_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_files flyway_history_files_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_files"
    ADD CONSTRAINT "flyway_history_files_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_forms flyway_history_forms_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_forms"
    ADD CONSTRAINT "flyway_history_forms_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_gis flyway_history_gis_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_gis"
    ADD CONSTRAINT "flyway_history_gis_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_notifications flyway_history_notifications_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_notifications"
    ADD CONSTRAINT "flyway_history_notifications_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_pages flyway_history_pages_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_pages"
    ADD CONSTRAINT "flyway_history_pages_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_views flyway_history_views_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_views"
    ADD CONSTRAINT "flyway_history_views_pk" PRIMARY KEY ("installed_rank");


--
-- Name: flyway_history_workflow flyway_history_workflow_pk; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."flyway_history_workflow"
    ADD CONSTRAINT "flyway_history_workflow_pk" PRIMARY KEY ("installed_rank");


--
-- Name: forms forms_name_unique_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."forms"
    ADD CONSTRAINT "forms_name_unique_per_object" UNIQUE ("object_id", "name");


--
-- Name: forms forms_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."forms"
    ADD CONSTRAINT "forms_pkey" PRIMARY KEY ("id");


--
-- Name: idempotency_keys idempotency_keys_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."idempotency_keys"
    ADD CONSTRAINT "idempotency_keys_unique" UNIQUE NULLS NOT DISTINCT ("organization_id", "user_id", "key");


--
-- Name: notification_deliveries notification_deliveries_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_deliveries"
    ADD CONSTRAINT "notification_deliveries_pkey" PRIMARY KEY ("id");


--
-- Name: notification_deliveries notification_deliveries_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_deliveries"
    ADD CONSTRAINT "notification_deliveries_unique" UNIQUE ("notification_id", "user_id", "channel");


--
-- Name: notification_preferences notification_preferences_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_preferences"
    ADD CONSTRAINT "notification_preferences_pkey" PRIMARY KEY ("user_id", "channel");


--
-- Name: notification_receipts notification_receipts_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_receipts"
    ADD CONSTRAINT "notification_receipts_pkey" PRIMARY KEY ("notification_id", "user_id");


--
-- Name: notification_rules notification_rules_name_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_rules"
    ADD CONSTRAINT "notification_rules_name_unique" UNIQUE ("organization_id", "name");


--
-- Name: notification_rules notification_rules_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_rules"
    ADD CONSTRAINT "notification_rules_pkey" PRIMARY KEY ("id");


--
-- Name: notification_source_runs notification_source_runs_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_source_runs"
    ADD CONSTRAINT "notification_source_runs_pkey" PRIMARY KEY ("source");


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notifications"
    ADD CONSTRAINT "notifications_pkey" PRIMARY KEY ("id");


--
-- Name: notifications notifications_source_key_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notifications"
    ADD CONSTRAINT "notifications_source_key_unique" UNIQUE ("organization_id", "source", "source_key");


--
-- Name: object_actions object_actions_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."object_actions"
    ADD CONSTRAINT "object_actions_pkey" PRIMARY KEY ("object_id", "name");


--
-- Name: org_units org_units_code_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."org_units"
    ADD CONSTRAINT "org_units_code_unique" UNIQUE ("organization_id", "code");


--
-- Name: org_units org_units_org_id_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."org_units"
    ADD CONSTRAINT "org_units_org_id_unique" UNIQUE ("organization_id", "id");


--
-- Name: org_units org_units_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."org_units"
    ADD CONSTRAINT "org_units_pkey" PRIMARY KEY ("id");


--
-- Name: organizations organizations_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."organizations"
    ADD CONSTRAINT "organizations_pkey" PRIMARY KEY ("id");


--
-- Name: organizations organizations_slug_key; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."organizations"
    ADD CONSTRAINT "organizations_slug_key" UNIQUE ("slug");


--
-- Name: pages pages_kind_unique_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."pages"
    ADD CONSTRAINT "pages_kind_unique_per_object" UNIQUE ("object_id", "kind");


--
-- Name: pages pages_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."pages"
    ADD CONSTRAINT "pages_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: pages pages_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."pages"
    ADD CONSTRAINT "pages_pkey" PRIMARY KEY ("id");


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."permissions"
    ADD CONSTRAINT "permissions_pkey" PRIMARY KEY ("id");


--
-- Name: permissions permissions_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."permissions"
    ADD CONSTRAINT "permissions_unique" UNIQUE NULLS NOT DISTINCT ("role_id", "object_id", "action");


--
-- Name: relationships relationships_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: relationships relationships_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_pkey" PRIMARY KEY ("id");


--
-- Name: roles roles_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."roles"
    ADD CONSTRAINT "roles_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."roles"
    ADD CONSTRAINT "roles_pkey" PRIMARY KEY ("id");


--
-- Name: service_accounts service_accounts_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."service_accounts"
    ADD CONSTRAINT "service_accounts_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: service_accounts service_accounts_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."service_accounts"
    ADD CONSTRAINT "service_accounts_pkey" PRIMARY KEY ("id");


--
-- Name: stored_files stored_files_object_key_unique; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."stored_files"
    ADD CONSTRAINT "stored_files_object_key_unique" UNIQUE ("object_key");


--
-- Name: stored_files stored_files_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."stored_files"
    ADD CONSTRAINT "stored_files_pkey" PRIMARY KEY ("id");


--
-- Name: user_org_units user_org_units_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_org_units"
    ADD CONSTRAINT "user_org_units_pkey" PRIMARY KEY ("user_id", "unit_id");


--
-- Name: user_preferences user_preferences_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_preferences"
    ADD CONSTRAINT "user_preferences_pkey" PRIMARY KEY ("user_id");


--
-- Name: user_roles user_roles_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_roles"
    ADD CONSTRAINT "user_roles_pkey" PRIMARY KEY ("user_id", "role_id");


--
-- Name: users users_email_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."users"
    ADD CONSTRAINT "users_email_unique_per_org" UNIQUE ("organization_id", "email");


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."users"
    ADD CONSTRAINT "users_pkey" PRIMARY KEY ("id");


--
-- Name: views views_name_unique_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."views"
    ADD CONSTRAINT "views_name_unique_per_object" UNIQUE ("object_id", "name");


--
-- Name: views views_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."views"
    ADD CONSTRAINT "views_pkey" PRIMARY KEY ("id");


--
-- Name: workflows workflows_name_unique_per_org; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."workflows"
    ADD CONSTRAINT "workflows_name_unique_per_org" UNIQUE ("organization_id", "name");


--
-- Name: workflows workflows_one_per_object; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."workflows"
    ADD CONSTRAINT "workflows_one_per_object" UNIQUE ("object_id");


--
-- Name: workflows workflows_pkey; Type: CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."workflows"
    ADD CONSTRAINT "workflows_pkey" PRIMARY KEY ("id");


--
-- Name: contribuyente__00000000_org_idx; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "contribuyente__00000000_org_idx" ON "app_data"."contribuyente__00000000" USING "btree" ("organization_id");


--
-- Name: licencia__00000000_ix_5806ea1543; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "licencia__00000000_ix_5806ea1543" ON "app_data"."licencia__00000000" USING "btree" ("solicitante");


--
-- Name: licencia__00000000_ix_9e6ecc75d8; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "licencia__00000000_ix_9e6ecc75d8" ON "app_data"."licencia__00000000" USING "btree" ("predio");


--
-- Name: licencia__00000000_org_idx; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "licencia__00000000_org_idx" ON "app_data"."licencia__00000000" USING "btree" ("organization_id");


--
-- Name: licencia__00000000_state_idx; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "licencia__00000000_state_idx" ON "app_data"."licencia__00000000" USING "btree" ("workflow_state");


--
-- Name: predio__00000000_ix_8ab96cae8a; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "predio__00000000_ix_8ab96cae8a" ON "app_data"."predio__00000000" USING "btree" ("titular");


--
-- Name: predio__00000000_lote_gix; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "predio__00000000_lote_gix" ON "app_data"."predio__00000000" USING "gist" ("lote");


--
-- Name: predio__00000000_org_idx; Type: INDEX; Schema: app_data; Owner: -
--

CREATE INDEX "predio__00000000_org_idx" ON "app_data"."predio__00000000" USING "btree" ("organization_id");


--
-- Name: audit_log_correlation_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "audit_log_correlation_idx" ON "wasichai"."audit_log" USING "btree" ("organization_id", "correlation_id");


--
-- Name: audit_log_org_time_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "audit_log_org_time_idx" ON "wasichai"."audit_log" USING "btree" ("organization_id", "occurred_at" DESC);


--
-- Name: audit_log_record_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "audit_log_record_idx" ON "wasichai"."audit_log" USING "btree" ("object_name", "record_id");


--
-- Name: audit_log_user_time_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "audit_log_user_time_idx" ON "wasichai"."audit_log" USING "btree" ("organization_id", "user_id", "occurred_at" DESC);


--
-- Name: automation_runs_automation_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "automation_runs_automation_idx" ON "wasichai"."automation_runs" USING "btree" ("automation_id", "created_at" DESC);


--
-- Name: automation_runs_log_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "automation_runs_log_idx" ON "wasichai"."automation_runs" USING "btree" ("organization_id", "created_at" DESC);


--
-- Name: automation_runs_pending_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "automation_runs_pending_idx" ON "wasichai"."automation_runs" USING "btree" ("created_at") WHERE ("status" = 'PENDING'::"text");


--
-- Name: automations_object_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "automations_object_idx" ON "wasichai"."automations" USING "btree" ("organization_id", "object_id");


--
-- Name: custom_fields_object_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "custom_fields_object_idx" ON "wasichai"."custom_fields" USING "btree" ("object_id", "position");


--
-- Name: document_types_object_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "document_types_object_idx" ON "wasichai"."document_types" USING "btree" ("organization_id", "object_id");


--
-- Name: documents_one_valid_per_record; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE UNIQUE INDEX "documents_one_valid_per_record" ON "wasichai"."documents" USING "btree" ("document_type_id", "record_id") WHERE ("status" = 'VALID'::"text");


--
-- Name: documents_record_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "documents_record_idx" ON "wasichai"."documents" USING "btree" ("organization_id", "object_id", "record_id", "issued_at" DESC);


--
-- Name: field_permissions_field_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "field_permissions_field_idx" ON "wasichai"."field_permissions" USING "btree" ("field_id");


--
-- Name: flyway_history_automation_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_automation_s_idx" ON "wasichai"."flyway_history_automation" USING "btree" ("success");


--
-- Name: flyway_history_core_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_core_s_idx" ON "wasichai"."flyway_history_core" USING "btree" ("success");


--
-- Name: flyway_history_core_seed_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_core_seed_s_idx" ON "wasichai"."flyway_history_core_seed" USING "btree" ("success");


--
-- Name: flyway_history_documents_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_documents_s_idx" ON "wasichai"."flyway_history_documents" USING "btree" ("success");


--
-- Name: flyway_history_files_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_files_s_idx" ON "wasichai"."flyway_history_files" USING "btree" ("success");


--
-- Name: flyway_history_forms_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_forms_s_idx" ON "wasichai"."flyway_history_forms" USING "btree" ("success");


--
-- Name: flyway_history_gis_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_gis_s_idx" ON "wasichai"."flyway_history_gis" USING "btree" ("success");


--
-- Name: flyway_history_notifications_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_notifications_s_idx" ON "wasichai"."flyway_history_notifications" USING "btree" ("success");


--
-- Name: flyway_history_pages_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_pages_s_idx" ON "wasichai"."flyway_history_pages" USING "btree" ("success");


--
-- Name: flyway_history_views_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_views_s_idx" ON "wasichai"."flyway_history_views" USING "btree" ("success");


--
-- Name: flyway_history_workflow_s_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "flyway_history_workflow_s_idx" ON "wasichai"."flyway_history_workflow" USING "btree" ("success");


--
-- Name: idempotency_keys_created_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "idempotency_keys_created_idx" ON "wasichai"."idempotency_keys" USING "btree" ("created_at");


--
-- Name: notification_deliveries_due_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_deliveries_due_idx" ON "wasichai"."notification_deliveries" USING "btree" ("organization_id", "next_attempt_at") WHERE ("status" = 'PENDING'::"text");


--
-- Name: notification_deliveries_user_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_deliveries_user_idx" ON "wasichai"."notification_deliveries" USING "btree" ("user_id");


--
-- Name: notification_receipts_user_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_receipts_user_idx" ON "wasichai"."notification_receipts" USING "btree" ("user_id");


--
-- Name: notification_rules_object_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_rules_object_idx" ON "wasichai"."notification_rules" USING "btree" ("organization_id", "object_id");


--
-- Name: notification_targets_notification_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_targets_notification_idx" ON "wasichai"."notification_targets" USING "btree" ("notification_id");


--
-- Name: notification_targets_unit_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_targets_unit_idx" ON "wasichai"."notification_targets" USING "btree" ("unit_id") WHERE ("unit_id" IS NOT NULL);


--
-- Name: notification_targets_user_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notification_targets_user_idx" ON "wasichai"."notification_targets" USING "btree" ("user_id") WHERE ("user_id" IS NOT NULL);


--
-- Name: notifications_link_object_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notifications_link_object_idx" ON "wasichai"."notifications" USING "btree" ("link_object_id") WHERE ("link_object_id" IS NOT NULL);


--
-- Name: notifications_open_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notifications_open_idx" ON "wasichai"."notifications" USING "btree" ("organization_id", "publish_at" DESC) WHERE ("resolved_at" IS NULL);


--
-- Name: notifications_source_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "notifications_source_idx" ON "wasichai"."notifications" USING "btree" ("organization_id", "source") WHERE ("resolved_at" IS NULL);


--
-- Name: org_units_parent_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "org_units_parent_idx" ON "wasichai"."org_units" USING "btree" ("parent_id");


--
-- Name: relationships_source_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "relationships_source_idx" ON "wasichai"."relationships" USING "btree" ("source_object_id");


--
-- Name: relationships_target_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "relationships_target_idx" ON "wasichai"."relationships" USING "btree" ("target_object_id");


--
-- Name: stored_files_org_created_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "stored_files_org_created_idx" ON "wasichai"."stored_files" USING "btree" ("organization_id", "created_at");


--
-- Name: user_org_units_unit_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "user_org_units_unit_idx" ON "wasichai"."user_org_units" USING "btree" ("unit_id");


--
-- Name: users_email_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "users_email_idx" ON "wasichai"."users" USING "btree" ("lower"("email"));


--
-- Name: views_one_default_per_object; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE UNIQUE INDEX "views_one_default_per_object" ON "wasichai"."views" USING "btree" ("object_id") WHERE "is_default";


--
-- Name: workflows_org_idx; Type: INDEX; Schema: wasichai; Owner: -
--

CREATE INDEX "workflows_org_idx" ON "wasichai"."workflows" USING "btree" ("organization_id");


--
-- Name: audit_log audit_log_append_only; Type: TRIGGER; Schema: wasichai; Owner: -
--

CREATE TRIGGER "audit_log_append_only" BEFORE DELETE OR UPDATE ON "wasichai"."audit_log" FOR EACH ROW EXECUTE FUNCTION "wasichai"."audit_log_guard"();


--
-- Name: audit_log audit_log_no_truncate; Type: TRIGGER; Schema: wasichai; Owner: -
--

CREATE TRIGGER "audit_log_no_truncate" BEFORE TRUNCATE ON "wasichai"."audit_log" FOR EACH STATEMENT EXECUTE FUNCTION "wasichai"."audit_log_guard"();


--
-- Name: licencia__00000000 licencia__00000000_predio_fkey; Type: FK CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."licencia__00000000"
    ADD CONSTRAINT "licencia__00000000_predio_fkey" FOREIGN KEY ("predio") REFERENCES "app_data"."predio__00000000"("id") ON DELETE SET NULL;


--
-- Name: licencia__00000000 licencia__00000000_solicitante_fkey; Type: FK CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."licencia__00000000"
    ADD CONSTRAINT "licencia__00000000_solicitante_fkey" FOREIGN KEY ("solicitante") REFERENCES "app_data"."contribuyente__00000000"("id") ON DELETE SET NULL;


--
-- Name: predio__00000000 predio__00000000_titular_fkey; Type: FK CONSTRAINT; Schema: app_data; Owner: -
--

ALTER TABLE ONLY "app_data"."predio__00000000"
    ADD CONSTRAINT "predio__00000000_titular_fkey" FOREIGN KEY ("titular") REFERENCES "app_data"."contribuyente__00000000"("id") ON DELETE SET NULL;


--
-- Name: audit_log audit_log_document_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."audit_log"
    ADD CONSTRAINT "audit_log_document_id_fkey" FOREIGN KEY ("document_id") REFERENCES "wasichai"."documents"("id") ON DELETE SET NULL;


--
-- Name: automation_runs automation_runs_automation_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automation_runs"
    ADD CONSTRAINT "automation_runs_automation_id_fkey" FOREIGN KEY ("automation_id") REFERENCES "wasichai"."automations"("id") ON DELETE CASCADE;


--
-- Name: automation_runs automation_runs_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automation_runs"
    ADD CONSTRAINT "automation_runs_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: automation_runs automation_runs_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automation_runs"
    ADD CONSTRAINT "automation_runs_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE SET NULL;


--
-- Name: automations automations_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automations"
    ADD CONSTRAINT "automations_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: automations automations_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."automations"
    ADD CONSTRAINT "automations_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: custom_fields custom_fields_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_fields"
    ADD CONSTRAINT "custom_fields_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: custom_fields custom_fields_relation_target_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_fields"
    ADD CONSTRAINT "custom_fields_relation_target_object_id_fkey" FOREIGN KEY ("relation_target_object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE RESTRICT;


--
-- Name: custom_objects custom_objects_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."custom_objects"
    ADD CONSTRAINT "custom_objects_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: document_counters document_counters_document_type_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_counters"
    ADD CONSTRAINT "document_counters_document_type_id_fkey" FOREIGN KEY ("document_type_id") REFERENCES "wasichai"."document_types"("id") ON DELETE CASCADE;


--
-- Name: document_types document_types_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_types"
    ADD CONSTRAINT "document_types_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: document_types document_types_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."document_types"
    ADD CONSTRAINT "document_types_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: documents documents_document_type_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_document_type_id_fkey" FOREIGN KEY ("document_type_id") REFERENCES "wasichai"."document_types"("id") ON DELETE RESTRICT;


--
-- Name: documents documents_issued_by_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_issued_by_fkey" FOREIGN KEY ("issued_by") REFERENCES "wasichai"."users"("id") ON DELETE SET NULL;


--
-- Name: documents documents_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: documents documents_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."documents"
    ADD CONSTRAINT "documents_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: field_permissions field_permissions_field_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."field_permissions"
    ADD CONSTRAINT "field_permissions_field_id_fkey" FOREIGN KEY ("field_id") REFERENCES "wasichai"."custom_fields"("id") ON DELETE CASCADE;


--
-- Name: field_permissions field_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."field_permissions"
    ADD CONSTRAINT "field_permissions_role_id_fkey" FOREIGN KEY ("role_id") REFERENCES "wasichai"."roles"("id") ON DELETE CASCADE;


--
-- Name: forms forms_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."forms"
    ADD CONSTRAINT "forms_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: forms forms_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."forms"
    ADD CONSTRAINT "forms_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: idempotency_keys idempotency_keys_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."idempotency_keys"
    ADD CONSTRAINT "idempotency_keys_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: idempotency_keys idempotency_keys_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."idempotency_keys"
    ADD CONSTRAINT "idempotency_keys_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: notification_deliveries notification_deliveries_notification_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_deliveries"
    ADD CONSTRAINT "notification_deliveries_notification_id_fkey" FOREIGN KEY ("notification_id") REFERENCES "wasichai"."notifications"("id") ON DELETE CASCADE;


--
-- Name: notification_deliveries notification_deliveries_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_deliveries"
    ADD CONSTRAINT "notification_deliveries_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: notification_deliveries notification_deliveries_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_deliveries"
    ADD CONSTRAINT "notification_deliveries_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: notification_preferences notification_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_preferences"
    ADD CONSTRAINT "notification_preferences_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: notification_receipts notification_receipts_notification_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_receipts"
    ADD CONSTRAINT "notification_receipts_notification_id_fkey" FOREIGN KEY ("notification_id") REFERENCES "wasichai"."notifications"("id") ON DELETE CASCADE;


--
-- Name: notification_receipts notification_receipts_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_receipts"
    ADD CONSTRAINT "notification_receipts_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: notification_rules notification_rules_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_rules"
    ADD CONSTRAINT "notification_rules_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: notification_rules notification_rules_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_rules"
    ADD CONSTRAINT "notification_rules_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: notification_targets notification_targets_notification_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_targets"
    ADD CONSTRAINT "notification_targets_notification_id_fkey" FOREIGN KEY ("notification_id") REFERENCES "wasichai"."notifications"("id") ON DELETE CASCADE;


--
-- Name: notification_targets notification_targets_unit_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_targets"
    ADD CONSTRAINT "notification_targets_unit_id_fkey" FOREIGN KEY ("unit_id") REFERENCES "wasichai"."org_units"("id") ON DELETE CASCADE;


--
-- Name: notification_targets notification_targets_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notification_targets"
    ADD CONSTRAINT "notification_targets_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: notifications notifications_created_by_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notifications"
    ADD CONSTRAINT "notifications_created_by_fkey" FOREIGN KEY ("created_by") REFERENCES "wasichai"."users"("id") ON DELETE SET NULL;


--
-- Name: notifications notifications_link_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notifications"
    ADD CONSTRAINT "notifications_link_object_id_fkey" FOREIGN KEY ("link_object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE SET NULL;


--
-- Name: notifications notifications_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."notifications"
    ADD CONSTRAINT "notifications_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: object_actions object_actions_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."object_actions"
    ADD CONSTRAINT "object_actions_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: org_units org_units_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."org_units"
    ADD CONSTRAINT "org_units_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: org_units org_units_parent_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."org_units"
    ADD CONSTRAINT "org_units_parent_fkey" FOREIGN KEY ("organization_id", "parent_id") REFERENCES "wasichai"."org_units"("organization_id", "id");


--
-- Name: pages pages_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."pages"
    ADD CONSTRAINT "pages_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: pages pages_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."pages"
    ADD CONSTRAINT "pages_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: permissions permissions_declared_action_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."permissions"
    ADD CONSTRAINT "permissions_declared_action_fkey" FOREIGN KEY ("declared_object_id", "action") REFERENCES "wasichai"."object_actions"("object_id", "name") ON DELETE CASCADE;


--
-- Name: permissions permissions_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."permissions"
    ADD CONSTRAINT "permissions_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: permissions permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."permissions"
    ADD CONSTRAINT "permissions_role_id_fkey" FOREIGN KEY ("role_id") REFERENCES "wasichai"."roles"("id") ON DELETE CASCADE;


--
-- Name: relationships relationships_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: relationships relationships_source_field_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_source_field_id_fkey" FOREIGN KEY ("relation_field_id") REFERENCES "wasichai"."custom_fields"("id") ON DELETE SET NULL;


--
-- Name: relationships relationships_source_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_source_object_id_fkey" FOREIGN KEY ("source_object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: relationships relationships_target_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."relationships"
    ADD CONSTRAINT "relationships_target_object_id_fkey" FOREIGN KEY ("target_object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: roles roles_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."roles"
    ADD CONSTRAINT "roles_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: service_accounts service_accounts_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."service_accounts"
    ADD CONSTRAINT "service_accounts_id_fkey" FOREIGN KEY ("id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: service_accounts service_accounts_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."service_accounts"
    ADD CONSTRAINT "service_accounts_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: stored_files stored_files_created_by_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."stored_files"
    ADD CONSTRAINT "stored_files_created_by_fkey" FOREIGN KEY ("created_by") REFERENCES "wasichai"."users"("id") ON DELETE SET NULL;


--
-- Name: user_org_units user_org_units_unit_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_org_units"
    ADD CONSTRAINT "user_org_units_unit_id_fkey" FOREIGN KEY ("unit_id") REFERENCES "wasichai"."org_units"("id") ON DELETE CASCADE;


--
-- Name: user_org_units user_org_units_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_org_units"
    ADD CONSTRAINT "user_org_units_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: user_preferences user_preferences_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_preferences"
    ADD CONSTRAINT "user_preferences_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: user_roles user_roles_role_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_roles"
    ADD CONSTRAINT "user_roles_role_id_fkey" FOREIGN KEY ("role_id") REFERENCES "wasichai"."roles"("id") ON DELETE CASCADE;


--
-- Name: user_roles user_roles_user_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."user_roles"
    ADD CONSTRAINT "user_roles_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "wasichai"."users"("id") ON DELETE CASCADE;


--
-- Name: users users_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."users"
    ADD CONSTRAINT "users_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: views views_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."views"
    ADD CONSTRAINT "views_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: views views_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."views"
    ADD CONSTRAINT "views_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- Name: workflows workflows_object_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."workflows"
    ADD CONSTRAINT "workflows_object_id_fkey" FOREIGN KEY ("object_id") REFERENCES "wasichai"."custom_objects"("id") ON DELETE CASCADE;


--
-- Name: workflows workflows_organization_id_fkey; Type: FK CONSTRAINT; Schema: wasichai; Owner: -
--

ALTER TABLE ONLY "wasichai"."workflows"
    ADD CONSTRAINT "workflows_organization_id_fkey" FOREIGN KEY ("organization_id") REFERENCES "wasichai"."organizations"("id") ON DELETE CASCADE;


--
-- PostgreSQL database dump complete
--

\unrestrict wasichaidemo

