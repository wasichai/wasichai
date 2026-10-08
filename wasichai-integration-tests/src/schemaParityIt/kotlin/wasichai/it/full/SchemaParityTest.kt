package wasichai.it.full

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.sql.DriverManager

// the metadata schema wasichai's migrations build is the one the original's V1-V14 built, fact by
// fact (catalog.sql). the expected side comes from the original's own migrations
// (generate-expected.sh). a line on one side only is a schema difference.
class SchemaParityTest : FullAppIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    // differences accepted on purpose: "<catalog line>" to "<reason, ADR or ruling>", one entry per catalog line.
    // each entry cites its ADR-031 D-entry.
    private val knownDeviations: Map<String, String> =
        mapOf(
            "column audit_log.reason #11 text" to "ADR-031 D25: change reason on record writes",
            "column custom_objects.append_only #13 boolean NOT NULL DEFAULT false" to "ADR-031 D24: append-only and api-only objects",
            "column custom_objects.api_only #14 boolean NOT NULL DEFAULT false" to "ADR-031 D24: append-only and api-only objects",
            "constraint custom_objects.custom_objects_append_only_not_null NOT NULL append_only" to
                "ADR-031 D24: append-only and api-only objects",
            "constraint custom_objects.custom_objects_api_only_not_null NOT NULL api_only" to
                "ADR-031 D24: append-only and api-only objects",
            "column custom_objects.requires_reason #15 boolean NOT NULL DEFAULT false" to "ADR-031 D25: change reason on record writes",
            "constraint custom_objects.custom_objects_requires_reason_not_null NOT NULL requires_reason" to
                "ADR-031 D25: change reason on record writes",
            "column user_preferences.locale #3 text" to "ADR-031 D18: per-user theme and locale",
            "column user_preferences.theme #2 text NOT NULL DEFAULT 'system'::text" to
                "ADR-031 D18: per-user theme and locale",
            "column user_preferences.updated_at #4 timestamp with time zone NOT NULL DEFAULT now()" to
                "ADR-031 D18: per-user theme and locale",
            "column user_preferences.user_id #1 uuid NOT NULL" to "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_pkey PRIMARY KEY (user_id)" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_theme_not_null NOT NULL theme" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_updated_at_not_null NOT NULL updated_at" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES META.users(id) ON DELETE CASCADE" to
                "ADR-031 D18: per-user theme and locale",
            "constraint user_preferences.user_preferences_user_id_not_null NOT NULL user_id" to
                "ADR-031 D18: per-user theme and locale",
            "index user_preferences.user_preferences_pkey CREATE UNIQUE INDEX user_preferences_pkey ON META.user_preferences USING btree (user_id)" to
                "ADR-031 D18: per-user theme and locale",
            "table user_preferences" to "ADR-031 D18: per-user theme and locale",
            // core's V3 adds a column to custom_fields before gis adds its own, so on a fresh database
            // the three gis columns sit one place later than in the original
            "column custom_fields.indexed #19 boolean NOT NULL DEFAULT false" to "ADR-031 D22: declared indexes",
            "constraint custom_fields.custom_fields_indexed_not_null NOT NULL indexed" to "ADR-031 D22: declared indexes",
            "column custom_objects.indexes #11 jsonb NOT NULL DEFAULT '[]'::jsonb" to "ADR-031 D22: declared indexes",
            "constraint custom_objects.custom_objects_indexes_not_null NOT NULL indexes" to "ADR-031 D22: declared indexes",
            "column custom_objects.unique_constraints #12 jsonb NOT NULL DEFAULT '[]'::jsonb" to "ADR-031 D23: declared composite uniques",
            "constraint custom_objects.custom_objects_unique_constraints_not_null NOT NULL unique_constraints" to
                "ADR-031 D23: declared composite uniques",
            "column custom_fields.geometry_type #19 text" to "ADR-031 D22: shifted by custom_fields.indexed",
            "column custom_fields.srid #20 integer" to "ADR-031 D22: shifted by custom_fields.indexed",
            "column custom_fields.dimension #21 integer" to "ADR-031 D22: shifted by custom_fields.indexed",
            "column custom_fields.geometry_type #20 text" to "ADR-031 D22: shifted by custom_fields.indexed",
            "column custom_fields.srid #21 integer" to "ADR-031 D22: shifted by custom_fields.indexed",
            "column custom_fields.dimension #22 integer" to "ADR-031 D22: shifted by custom_fields.indexed"
        ) +
            // app-declared actions (ADR-031 D26): a new table, and permissions learns to point at it
            listOf(
                "constraint permissions.permissions_action_valid CHECK ((action = ANY ($BUILT_IN_ACTIONS)))",
                "column object_actions.created_at #4 timestamp with time zone NOT NULL DEFAULT now()",
                "column object_actions.label #3 text NOT NULL",
                "column object_actions.name #2 text NOT NULL",
                "column object_actions.object_id #1 uuid NOT NULL",
                "column permissions.declared_object_id #6 uuid DEFAULT \nCASE\n    WHEN (action = ANY ($BUILT_IN_ACTIONS))" +
                    " THEN NULL::uuid\n    ELSE object_id\nEND",
                "constraint object_actions.object_actions_created_at_not_null NOT NULL created_at",
                "constraint object_actions.object_actions_label_not_null NOT NULL label",
                "constraint object_actions.object_actions_name_not_null NOT NULL name",
                "constraint object_actions.object_actions_name_valid CHECK ((name ~ '^[A-Z][A-Z0-9_]{1,48}\$'::text))",
                "constraint object_actions.object_actions_object_id_fkey FOREIGN KEY (object_id) " +
                    "REFERENCES META.custom_objects(id) ON DELETE CASCADE",
                "constraint object_actions.object_actions_object_id_not_null NOT NULL object_id",
                "constraint object_actions.object_actions_pkey PRIMARY KEY (object_id, name)",
                "constraint permissions.permissions_declared_action_fkey FOREIGN KEY (declared_object_id, action) " +
                    "REFERENCES META.object_actions(object_id, name) ON DELETE CASCADE",
                "index object_actions.object_actions_pkey CREATE UNIQUE INDEX object_actions_pkey ON META.object_actions USING btree (object_id, name)",
                "table object_actions"
            ).associateWith { "ADR-031 D26: app-declared actions" } +
            // service accounts (ADR-031 D27, ADR-043): a new table
            listOf(
                "column service_accounts.created_at #6 timestamp with time zone NOT NULL DEFAULT now()",
                "column service_accounts.enabled #5 boolean NOT NULL DEFAULT true",
                "column service_accounts.id #1 uuid NOT NULL",
                "column service_accounts.name #3 text NOT NULL",
                "column service_accounts.organization_id #2 uuid NOT NULL",
                "column service_accounts.secret_hash #4 text NOT NULL",
                "column service_accounts.secret_rotated_at #7 timestamp with time zone NOT NULL DEFAULT now()",
                "constraint service_accounts.service_accounts_created_at_not_null NOT NULL created_at",
                "constraint service_accounts.service_accounts_enabled_not_null NOT NULL enabled",
                "constraint service_accounts.service_accounts_id_fkey FOREIGN KEY (id) REFERENCES META.users(id) ON DELETE CASCADE",
                "constraint service_accounts.service_accounts_id_not_null NOT NULL id",
                "constraint service_accounts.service_accounts_name_not_null NOT NULL name",
                "constraint service_accounts.service_accounts_name_unique_per_org UNIQUE (organization_id, name)",
                "constraint service_accounts.service_accounts_name_valid CHECK ((name ~ '^[a-z][a-z0-9_-]{1,48}\$'::text))",
                "constraint service_accounts.service_accounts_organization_id_fkey FOREIGN KEY (organization_id) " +
                    "REFERENCES META.organizations(id) ON DELETE CASCADE",
                "constraint service_accounts.service_accounts_organization_id_not_null NOT NULL organization_id",
                "constraint service_accounts.service_accounts_pkey PRIMARY KEY (id)",
                "constraint service_accounts.service_accounts_secret_hash_not_null NOT NULL secret_hash",
                "constraint service_accounts.service_accounts_secret_rotated_at_not_null NOT NULL secret_rotated_at",
                "index service_accounts.service_accounts_name_unique_per_org " +
                    "CREATE UNIQUE INDEX service_accounts_name_unique_per_org ON META.service_accounts USING btree (organization_id, name)",
                "index service_accounts.service_accounts_pkey CREATE UNIQUE INDEX service_accounts_pkey ON META.service_accounts USING btree (id)",
                "table service_accounts"
            ).associateWith { "ADR-031 D27: service accounts" } +
            // organizational units (ADR-031 D31, ADR-045): two new core tables
            listOf(
                "column org_units.code #4 text NOT NULL",
                "column org_units.created_at #6 timestamp with time zone NOT NULL DEFAULT now()",
                "column org_units.id #1 uuid NOT NULL DEFAULT gen_random_uuid()",
                "column org_units.label #5 text NOT NULL",
                "column org_units.organization_id #2 uuid NOT NULL",
                "column org_units.parent_id #3 uuid",
                "column org_units.updated_at #7 timestamp with time zone NOT NULL DEFAULT now()",
                "column user_org_units.unit_id #2 uuid NOT NULL",
                "column user_org_units.user_id #1 uuid NOT NULL",
                "constraint org_units.org_units_code_not_null NOT NULL code",
                "constraint org_units.org_units_code_unique UNIQUE (organization_id, code)",
                "constraint org_units.org_units_code_valid CHECK ((code ~ '^[A-Z][A-Z0-9_]{1,48}\$'::text))",
                "constraint org_units.org_units_created_at_not_null NOT NULL created_at",
                "constraint org_units.org_units_id_not_null NOT NULL id",
                "constraint org_units.org_units_label_not_null NOT NULL label",
                "constraint org_units.org_units_label_valid CHECK (((length(label) >= 1) AND (length(label) <= 120)))",
                "constraint org_units.org_units_not_own_parent CHECK ((parent_id <> id))",
                "constraint org_units.org_units_org_id_unique UNIQUE (organization_id, id)",
                "constraint org_units.org_units_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES META.organizations(id) ON DELETE CASCADE",
                "constraint org_units.org_units_organization_id_not_null NOT NULL organization_id",
                "constraint org_units.org_units_parent_fkey FOREIGN KEY (organization_id, parent_id) REFERENCES META.org_units(organization_id, id)",
                "constraint org_units.org_units_pkey PRIMARY KEY (id)",
                "constraint org_units.org_units_updated_at_not_null NOT NULL updated_at",
                "constraint user_org_units.user_org_units_pkey PRIMARY KEY (user_id, unit_id)",
                "constraint user_org_units.user_org_units_unit_id_fkey FOREIGN KEY (unit_id) REFERENCES META.org_units(id) ON DELETE CASCADE",
                "constraint user_org_units.user_org_units_unit_id_not_null NOT NULL unit_id",
                "constraint user_org_units.user_org_units_user_id_fkey FOREIGN KEY (user_id) REFERENCES META.users(id) ON DELETE CASCADE",
                "constraint user_org_units.user_org_units_user_id_not_null NOT NULL user_id",
                "index org_units.org_units_code_unique CREATE UNIQUE INDEX org_units_code_unique ON META.org_units USING btree (organization_id, code)",
                "index org_units.org_units_org_id_unique CREATE UNIQUE INDEX org_units_org_id_unique ON META.org_units USING btree (organization_id, id)",
                "index org_units.org_units_parent_idx CREATE INDEX org_units_parent_idx ON META.org_units USING btree (parent_id)",
                "index org_units.org_units_pkey CREATE UNIQUE INDEX org_units_pkey ON META.org_units USING btree (id)",
                "index user_org_units.user_org_units_pkey CREATE UNIQUE INDEX user_org_units_pkey ON META.user_org_units USING btree (user_id, unit_id)",
                "index user_org_units.user_org_units_unit_idx CREATE INDEX user_org_units_unit_idx ON META.user_org_units USING btree (unit_id)",
                "table org_units",
                "table user_org_units"
            ).associateWith { "ADR-031 D31: organizational units" } +
            // notifications (ADR-031 D32, ADR-046): the module's tables
            listOf(
                "column notification_receipts.dismissed_at #4 timestamp with time zone",
                "column notification_receipts.notification_id #1 uuid NOT NULL",
                "column notification_receipts.read_at #3 timestamp with time zone",
                "column notification_receipts.snoozed_until #5 timestamp with time zone",
                "column notification_receipts.user_id #2 uuid NOT NULL",
                "column notification_rules.created_at #8 timestamp with time zone NOT NULL DEFAULT now()",
                "column notification_rules.definition #7 jsonb NOT NULL",
                "column notification_rules.enabled #6 boolean NOT NULL DEFAULT true",
                "column notification_rules.id #1 uuid NOT NULL DEFAULT gen_random_uuid()",
                "column notification_rules.label #5 text NOT NULL",
                "column notification_rules.name #4 text NOT NULL",
                "column notification_rules.object_id #3 uuid NOT NULL",
                "column notification_rules.organization_id #2 uuid NOT NULL",
                "column notification_rules.updated_at #9 timestamp with time zone NOT NULL DEFAULT now()",
                "column notification_source_runs.last_run_at #2 timestamp with time zone NOT NULL",
                "column notification_source_runs.source #1 text NOT NULL",
                "column notification_targets.notification_id #1 uuid NOT NULL",
                "column notification_targets.role_name #4 text",
                "column notification_targets.type #2 text NOT NULL",
                "column notification_targets.unit_id #5 uuid",
                "column notification_targets.user_id #3 uuid",
                "column notifications.body #5 text",
                "column notifications.created_at #16 timestamp with time zone NOT NULL DEFAULT now()",
                "column notifications.created_by #15 uuid",
                "column notifications.due_at #10 timestamp with time zone",
                "column notifications.expires_at #9 timestamp with time zone",
                "column notifications.fingerprint #13 text NOT NULL",
                "column notifications.id #1 uuid NOT NULL DEFAULT gen_random_uuid()",
                "column notifications.kind #3 text NOT NULL",
                "column notifications.link #6 jsonb",
                "column notifications.link_object_id #7 uuid",
                "column notifications.organization_id #2 uuid NOT NULL",
                "column notifications.publish_at #8 timestamp with time zone NOT NULL DEFAULT now()",
                "column notifications.resolved_at #14 timestamp with time zone",
                "column notifications.source #11 text NOT NULL",
                "column notifications.source_key #12 text",
                "column notifications.title #4 text NOT NULL",
                "column notifications.updated_at #17 timestamp with time zone NOT NULL DEFAULT now()",
                "constraint notification_receipts.notification_receipts_notification_id_fkey FOREIGN KEY (notification_id) REFERENCES " +
                    "META.notifications(id) ON DELETE CASCADE",
                "constraint notification_receipts.notification_receipts_notification_id_not_null NOT NULL notification_id",
                "constraint notification_receipts.notification_receipts_pkey PRIMARY KEY (notification_id, user_id)",
                "constraint notification_receipts.notification_receipts_user_id_fkey FOREIGN KEY (user_id) REFERENCES META.users(id) ON DELETE CASCADE",
                "constraint notification_receipts.notification_receipts_user_id_not_null NOT NULL user_id",
                "constraint notification_rules.notification_rules_created_at_not_null NOT NULL created_at",
                "constraint notification_rules.notification_rules_definition_not_null NOT NULL definition",
                "constraint notification_rules.notification_rules_enabled_not_null NOT NULL enabled",
                "constraint notification_rules.notification_rules_id_not_null NOT NULL id",
                "constraint notification_rules.notification_rules_label_not_null NOT NULL label",
                "constraint notification_rules.notification_rules_name_not_null NOT NULL name",
                "constraint notification_rules.notification_rules_name_unique UNIQUE (organization_id, name)",
                "constraint notification_rules.notification_rules_name_valid CHECK ((name ~ '^[a-z][a-z0-9_]{1,48}\$'::text))",
                "constraint notification_rules.notification_rules_object_id_fkey FOREIGN KEY (object_id) REFERENCES META.custom_objects(id) ON " +
                    "DELETE CASCADE",
                "constraint notification_rules.notification_rules_object_id_not_null NOT NULL object_id",
                "constraint notification_rules.notification_rules_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES " +
                    "META.organizations(id) ON DELETE CASCADE",
                "constraint notification_rules.notification_rules_organization_id_not_null NOT NULL organization_id",
                "constraint notification_rules.notification_rules_pkey PRIMARY KEY (id)",
                "constraint notification_rules.notification_rules_updated_at_not_null NOT NULL updated_at",
                "constraint notification_source_runs.notification_source_runs_last_run_at_not_null NOT NULL last_run_at",
                "constraint notification_source_runs.notification_source_runs_pkey PRIMARY KEY (source)",
                "constraint notification_source_runs.notification_source_runs_source_not_null NOT NULL source",
                "constraint notification_targets.notification_targets_notification_id_fkey FOREIGN KEY (notification_id) REFERENCES " +
                    "META.notifications(id) ON DELETE CASCADE",
                "constraint notification_targets.notification_targets_notification_id_not_null NOT NULL notification_id",
                "constraint notification_targets.notification_targets_shape CHECK ((((type = 'USER'::text) = (user_id IS NOT NULL)) AND ((type = " +
                    "'ROLE'::text) = (role_name IS NOT NULL)) AND ((type = 'UNIT'::text) = (unit_id IS NOT NULL))))",
                "constraint notification_targets.notification_targets_type_not_null NOT NULL type",
                "constraint notification_targets.notification_targets_type_valid CHECK ((type = ANY (ARRAY['ALL'::text, 'USER'::text, 'ROLE'::text, " +
                    "'UNIT'::text])))",
                "constraint notification_targets.notification_targets_unit_id_fkey FOREIGN KEY (unit_id) REFERENCES META.org_units(id) ON DELETE CASCADE",
                "constraint notification_targets.notification_targets_user_id_fkey FOREIGN KEY (user_id) REFERENCES META.users(id) ON DELETE CASCADE",
                "constraint notifications.notifications_created_at_not_null NOT NULL created_at",
                "constraint notifications.notifications_created_by_fkey FOREIGN KEY (created_by) REFERENCES META.users(id) ON DELETE SET NULL",
                "constraint notifications.notifications_fingerprint_not_null NOT NULL fingerprint",
                "constraint notifications.notifications_id_not_null NOT NULL id",
                "constraint notifications.notifications_kind_not_null NOT NULL kind",
                "constraint notifications.notifications_kind_valid CHECK ((kind = ANY (ARRAY['INFO'::text, 'WARNING'::text, 'ACTION'::text])))",
                "constraint notifications.notifications_link_object_id_fkey FOREIGN KEY (link_object_id) REFERENCES META.custom_objects(id) ON " +
                    "DELETE SET NULL",
                "constraint notifications.notifications_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES META.organizations(id) ON " +
                    "DELETE CASCADE",
                "constraint notifications.notifications_organization_id_not_null NOT NULL organization_id",
                "constraint notifications.notifications_pkey PRIMARY KEY (id)",
                "constraint notifications.notifications_publish_at_not_null NOT NULL publish_at",
                "constraint notifications.notifications_source_key_unique UNIQUE (organization_id, source, source_key)",
                "constraint notifications.notifications_source_not_null NOT NULL source",
                "constraint notifications.notifications_title_not_null NOT NULL title",
                "constraint notifications.notifications_updated_at_not_null NOT NULL updated_at",
                "constraint notifications.notifications_window_valid CHECK (((expires_at IS NULL) OR (expires_at > publish_at)))",
                "index notification_receipts.notification_receipts_pkey CREATE UNIQUE INDEX notification_receipts_pkey ON META.notification_receipts " +
                    "USING btree (notification_id, user_id)",
                "index notification_receipts.notification_receipts_user_idx CREATE INDEX notification_receipts_user_idx ON " +
                    "META.notification_receipts USING btree (user_id)",
                "index notification_rules.notification_rules_name_unique CREATE UNIQUE INDEX notification_rules_name_unique ON " +
                    "META.notification_rules USING btree (organization_id, name)",
                "index notification_rules.notification_rules_object_idx CREATE INDEX notification_rules_object_idx ON META.notification_rules USING " +
                    "btree (organization_id, object_id)",
                "index notification_rules.notification_rules_pkey CREATE UNIQUE INDEX notification_rules_pkey ON META.notification_rules USING btree (id)",
                "index notification_source_runs.notification_source_runs_pkey CREATE UNIQUE INDEX notification_source_runs_pkey ON " +
                    "META.notification_source_runs USING btree (source)",
                "index notification_targets.notification_targets_notification_idx CREATE INDEX notification_targets_notification_idx ON " +
                    "META.notification_targets USING btree (notification_id)",
                "index notification_targets.notification_targets_unit_idx CREATE INDEX notification_targets_unit_idx ON META.notification_targets " +
                    "USING btree (unit_id) WHERE (unit_id IS NOT NULL)",
                "index notification_targets.notification_targets_user_idx CREATE INDEX notification_targets_user_idx ON META.notification_targets " +
                    "USING btree (user_id) WHERE (user_id IS NOT NULL)",
                "index notifications.notifications_link_object_idx CREATE INDEX notifications_link_object_idx ON META.notifications USING btree " +
                    "(link_object_id) WHERE (link_object_id IS NOT NULL)",
                "index notifications.notifications_open_idx CREATE INDEX notifications_open_idx ON META.notifications USING btree (organization_id, " +
                    "publish_at DESC) WHERE (resolved_at IS NULL)",
                "index notifications.notifications_pkey CREATE UNIQUE INDEX notifications_pkey ON META.notifications USING btree (id)",
                "index notifications.notifications_source_idx CREATE INDEX notifications_source_idx ON META.notifications USING btree " +
                    "(organization_id, source) WHERE (resolved_at IS NULL)",
                "index notifications.notifications_source_key_unique CREATE UNIQUE INDEX notifications_source_key_unique ON META.notifications USING " +
                    "btree (organization_id, source, source_key)",
                "table notification_receipts",
                "table notification_rules",
                "table notification_source_runs",
                "table notification_targets",
                "table notifications"
            ).associateWith { "ADR-031 D32: notifications" } +
            // correlation id and source on audit rows, the id kept on queued runs (ADR-031 D35, ADR-050)
            listOf(
                "column audit_log.correlation_id #12 text",
                "column audit_log.source #13 text",
                "constraint audit_log.audit_log_correlation_id_valid CHECK ((correlation_id ~ '^[A-Za-z0-9._-]{1,64}\$'::text)) NOT VALID",
                "constraint audit_log.audit_log_source_valid CHECK ((source ~ '^[A-Za-z0-9._:-]{1,64}\$'::text)) NOT VALID",
                "index audit_log.audit_log_correlation_idx CREATE INDEX audit_log_correlation_idx ON META.audit_log USING " +
                    "btree (organization_id, correlation_id)",
                "column automation_runs.correlation_id #17 text"
            ).associateWith { "ADR-031 D35: correlation id and change source" } +
            // MANAGE_TENANTS joins the built-in actions, object-less only (ADR-031 D41, ADR-055). the declared_object_id
            // expression above keeps the six: an object-less row is null there either way
            listOf(
                "constraint object_actions.object_actions_not_builtin CHECK ((name <> ALL ($BUILT_IN_ACTIONS_V15)))",
                "constraint permissions.permissions_action_valid CHECK (((action = ANY ($BUILT_IN_ACTIONS_V15)) OR (object_id IS NOT NULL)))",
                "constraint permissions.permissions_tenants_no_object CHECK (((action <> 'MANAGE_TENANTS'::text) OR (object_id IS NULL)))"
            ).associateWith { "ADR-031 D41: MANAGE_TENANTS" } +
            // the audit list by user and period (ADR-031 D37, ADR-052)
            mapOf(
                "index audit_log.audit_log_user_time_idx CREATE INDEX audit_log_user_time_idx ON META.audit_log USING btree " +
                    "(organization_id, user_id, occurred_at DESC)" to "ADR-031 D37: audit pages, period and user filters"
            ) +
            // audit_log refuses update, delete and truncate in the database (ADR-031 D39, ADR-054). a body is its md5:
            // change V13 or R__audit_purge_role.sql and this changes too (the purge role is empty here)
            listOf(
                "function audit_log_guard() returns trigger body bd309dadb39e9e30d129c364d8a751fe",
                "function audit_log_purge_role() returns text body 4c15c07985ba73997f80c5550bd9e0a2",
                "trigger audit_log.audit_log_append_only CREATE TRIGGER audit_log_append_only BEFORE DELETE OR UPDATE ON META.audit_log " +
                    "FOR EACH ROW EXECUTE FUNCTION META.audit_log_guard()",
                "trigger audit_log.audit_log_no_truncate CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON META.audit_log " +
                    "FOR EACH STATEMENT EXECUTE FUNCTION META.audit_log_guard()"
            ).associateWith { "ADR-031 D39: audit_log is append-only in the database" }

    @Test
    fun `the fixture is the original's whole schema`() {
        assertThat(expected().filter { it.startsWith("table ") }).contains(
            "table audit_log",
            "table automation_runs",
            "table automations",
            "table custom_fields",
            "table custom_objects",
            "table document_counters",
            "table document_types",
            "table documents",
            "table field_permissions",
            "table forms",
            "table organizations",
            "table pages",
            "table permissions",
            "table relationships",
            "table roles",
            "table user_roles",
            "table users",
            "table views",
            "table workflows"
        )
    }

    @Test
    fun `the metadata schema is the original's final schema`() {
        // the context is up, so every module migrated
        val actual = catalogOf("wasichai")
        val expected = expected()
        assertThat((expected - actual - knownDeviations.keys).sorted()).describedAs("in the original, not in wasichai").isEmpty()
        assertThat((actual - expected - knownDeviations.keys).sorted()).describedAs("in wasichai, not in the original").isEmpty()
    }

    private fun expected(): Set<String> = resource("legacy-final.catalog").lines().filter { it.isNotBlank() }.toSet()

    private fun resource(name: String): String = javaClass.getResource("/schema-parity/$name")!!.readText()

    private fun catalogOf(schema: String): Set<String> {
        require(Regex("[a-z_][a-z0-9_]*").matches(schema)) { "not a schema name: $schema" }
        val sql = resource("catalog.sql").replace("__SCHEMA__", schema)
        val url =
            "jdbc:postgresql://${environment.getProperty("wasichai.database.host")}:${environment.getProperty("wasichai.database.port")}/" +
                environment.getProperty("wasichai.database.name")
        return DriverManager
            .getConnection(url, environment.getProperty("wasichai.database.username"), environment.getProperty("wasichai.database.password"))
            .use { connection ->
                connection.createStatement().use { statement ->
                    // the connection's own search_path would start with "$user" = wasichai and hide the prefix
                    statement.execute("SET search_path TO public")
                    statement.executeQuery(sql).use { rows ->
                        buildSet { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
    }

    private companion object {
        // how the catalog spells the built-in action list
        const val BUILT_IN_ACTIONS =
            "ARRAY['READ'::text, 'CREATE'::text, 'UPDATE'::text, 'DELETE'::text, 'MANAGE_METADATA'::text, 'MANAGE_ORGANIZATION'::text]"

        // and since core's V15, MANAGE_TENANTS too
        const val BUILT_IN_ACTIONS_V15 =
            "ARRAY['READ'::text, 'CREATE'::text, 'UPDATE'::text, 'DELETE'::text, 'MANAGE_METADATA'::text, " +
                "'MANAGE_ORGANIZATION'::text, 'MANAGE_TENANTS'::text]"
    }
}
