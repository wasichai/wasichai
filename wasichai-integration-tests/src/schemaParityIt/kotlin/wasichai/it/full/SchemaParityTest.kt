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
                "constraint object_actions.object_actions_not_builtin CHECK ((name <> ALL ($BUILT_IN_ACTIONS)))",
                "constraint object_actions.object_actions_object_id_fkey FOREIGN KEY (object_id) " +
                    "REFERENCES META.custom_objects(id) ON DELETE CASCADE",
                "constraint object_actions.object_actions_object_id_not_null NOT NULL object_id",
                "constraint object_actions.object_actions_pkey PRIMARY KEY (object_id, name)",
                "constraint permissions.permissions_action_valid CHECK (((action = ANY ($BUILT_IN_ACTIONS)) OR (object_id IS NOT NULL)))",
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
                "constraint service_accounts.service_accounts_organization_id_fkey FOREIGN KEY (organization_id) REFERENCES META.organizations(id) ON DELETE CASCADE",
                "constraint service_accounts.service_accounts_organization_id_not_null NOT NULL organization_id",
                "constraint service_accounts.service_accounts_pkey PRIMARY KEY (id)",
                "constraint service_accounts.service_accounts_secret_hash_not_null NOT NULL secret_hash",
                "constraint service_accounts.service_accounts_secret_rotated_at_not_null NOT NULL secret_rotated_at",
                "index service_accounts.service_accounts_name_unique_per_org CREATE UNIQUE INDEX service_accounts_name_unique_per_org ON META.service_accounts USING btree (organization_id, name)",
                "index service_accounts.service_accounts_pkey CREATE UNIQUE INDEX service_accounts_pkey ON META.service_accounts USING btree (id)",
                "table service_accounts"
            ).associateWith { "ADR-031 D27: service accounts" }

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
    }
}
