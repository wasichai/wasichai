package wasichai.core.api

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import wasichai.core.audit.AuditLogOwnershipCheck
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiAuditProperties
import wasichai.core.platform.WasichaiDatabaseProperties
import wasichai.core.platform.WasichaiMigrations
import wasichai.core.platform.WasichaiSchemas
import wasichai.test.WasichaiIntegrationTest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

private const val META = "audit_guard_meta"
private const val PURGER = "wasichai_it_audit_purger"
private const val RUNTIME = "wasichai_it_audit_runtime"

// roles are cluster-wide and outlive the run: test-only names, a fixed test-only password
private const val ROLE_PASSWORD = "audit-guard-it"

// issue 58 (ADR-054): audit_log refuses UPDATE, DELETE and TRUNCATE in the database itself. a schema of its own: this
// context names a purge role, and the set-null test adds a foreign key the shared schema must not get.
@TestPropertySource(
    properties = [
        "wasichai.database.metadata-schema=$META",
        "wasichai.database.data-schema=audit_guard_data",
        "wasichai.audit.purge-role=$PURGER"
    ]
)
class AuditLogAppendOnlyApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var database: WasichaiDatabaseProperties

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var ownershipCheck: AuditLogOwnershipCheck

    @Test
    fun `inserts and reads are unaffected, through the api and in sql`() {
        val token = bearer()
        val name = uniqueName("guarded")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("name" to name, "label" to "Guarded", "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"))))
            .exchange()
            .expectStatus()
            .isCreated
        val created =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, token)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "G-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
        client
            .put()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, token)
            .bodyValue(mapOf("attributes" to mapOf("codigo" to "G-2")))
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/api/objects/$name/records/$id/history")
            .header(HttpHeaders.AUTHORIZATION, token)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(2)

        owner().use { connection ->
            val row = insertRow(connection)
            assertThat(reasonOf(connection, row)).isEqualTo("written once")
        }
    }

    @Test
    fun `update, delete and truncate fail with a clear error and change nothing`() {
        owner().use { connection ->
            val row = insertRow(connection)
            val before = count(connection)

            assertRefused(connection, "UPDATE", "UPDATE $META.audit_log SET reason = 'x'")
            assertRefused(connection, "DELETE", "DELETE FROM $META.audit_log")
            assertRefused(connection, "TRUNCATE", "TRUNCATE $META.audit_log")

            assertThat(count(connection)).isEqualTo(before)
            assertThat(reasonOf(connection, row)).isEqualTo("written once")
        }
    }

    // wasichai-documents' audit_log_document_id_fkey, ON DELETE SET NULL, played by a table of this schema
    @Test
    fun `a foreign-key action that only nulls document_id passes, the same update by hand does not`() {
        owner().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS $META.guard_documents (id uuid PRIMARY KEY)")
                statement.execute(
                    """
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'audit_log_document_id_fkey'
                                       AND conrelid = '$META.audit_log'::regclass) THEN
                            ALTER TABLE $META.audit_log ADD CONSTRAINT audit_log_document_id_fkey
                                FOREIGN KEY (document_id) REFERENCES $META.guard_documents (id) ON DELETE SET NULL;
                        END IF;
                    END $$
                    """.trimIndent()
                )
            }
            val document = UUID.randomUUID()
            execute(connection, "INSERT INTO $META.guard_documents (id) VALUES (?)", document)
            val row = insertRow(connection, document)
            val rest = "SELECT (to_jsonb(a) - 'document_id')::text FROM $META.audit_log a WHERE id = ?"
            val before = single(connection, rest, row)

            assertRefused(connection, "UPDATE", "UPDATE $META.audit_log SET document_id = NULL WHERE id = '$row'")
            assertThat(single(connection, "SELECT document_id::text FROM $META.audit_log WHERE id = ?", row)).isEqualTo(document.toString())

            assertThat(execute(connection, "DELETE FROM $META.guard_documents WHERE id = ?", document)).isEqualTo(1)
            assertThat(single(connection, "SELECT document_id::text FROM $META.audit_log WHERE id = ?", row)).isNull()
            assertThat(single(connection, rest, row)).isEqualTo(before)
        }
    }

    @Test
    fun `the purge flag lifts delete and truncate for a login as the configured role, and nothing else`() {
        owner().use { connection ->
            ensureRole(connection, PURGER)
            execute(connection, "GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON $META.audit_log TO $PURGER")
            assertThat(single(connection, "SELECT $META.audit_log_purge_role()")).isEqualTo(PURGER)
            val row = insertRow(connection)

            // the flag alone: the owner is not the purge role
            assertRefused(connection, "DELETE", "DELETE FROM $META.audit_log WHERE id = '$row'", purge = true)
            // nor is a session that only SET ROLEs to it: the login counts, not current_user
            assertRefused(connection, "DELETE", "DELETE FROM $META.audit_log WHERE id = '$row'", purge = true, setRole = PURGER)
            assertThat(reasonOf(connection, row)).isEqualTo("written once")
        }
        login(PURGER).use { connection ->
            val row = insertRow(connection)
            // the role alone: no flag, no purge
            assertRefused(connection, "DELETE", "DELETE FROM $META.audit_log WHERE id = '$row'")
            // a purge removes, it never rewrites
            assertRefused(connection, "UPDATE", "UPDATE $META.audit_log SET reason = 'x' WHERE id = '$row'", purge = true)

            inTransaction(connection, purge = true) {
                assertThat(execute(connection, "DELETE FROM $META.audit_log WHERE id = ?", row)).isEqualTo(1)
            }
            assertThat(count(connection, row)).isZero()

            val before = count(connection)
            inTransaction(connection, purge = true, commit = false) {
                execute(connection, "TRUNCATE $META.audit_log")
                assertThat(count(connection)).isZero()
            }
            assertThat(count(connection)).isEqualTo(before)
        }
    }

    // the two-role setup of the guide: the runtime role writes and reads the trail, and cannot lift the guard
    @Test
    fun `a runtime role that does not own audit_log inserts and reads, and can neither rename the purge role nor drop the guard`() {
        owner().use { connection ->
            ensureRole(connection, RUNTIME)
            execute(connection, "GRANT SELECT, INSERT ON $META.audit_log TO $RUNTIME")
        }
        login(RUNTIME).use { connection ->
            val row = insertRow(connection)
            assertThat(reasonOf(connection, row)).isEqualTo("written once")

            listOf(
                "UPDATE $META.audit_log SET reason = 'x' WHERE id = '$row'",
                "DELETE FROM $META.audit_log WHERE id = '$row'",
                "CREATE OR REPLACE FUNCTION $META.audit_log_purge_role() RETURNS text LANGUAGE sql AS 'SELECT ''$RUNTIME''::text'",
                "DROP TRIGGER audit_log_append_only ON $META.audit_log",
                "ALTER TABLE $META.audit_log DISABLE TRIGGER audit_log_append_only"
            ).forEach { sql -> assertThat(sqlState(connection, sql)).describedAs(sql).isEqualTo(INSUFFICIENT_PRIVILEGE) }
            assertThat(reasonOf(connection, row)).isEqualTo("written once")
            // the startup check has nothing to say about this role
            assertThat(single(connection, AuditLogOwnershipCheck.OWNER_QUERY.replace(":table", "?"), "$META.audit_log")).isNull()
        }
        owner().use { connection ->
            assertThat(single(connection, "SELECT $META.audit_log_purge_role()")).isEqualTo(PURGER)
        }
    }

    @Test
    fun `the startup check warns when the role wasichai runs as owns audit_log`() {
        // the test database's user created every table here: the owner, so a WARN
        val warning = runBlocking { ownershipCheck.check() }

        assertThat(warning).contains("'${database.username}'").contains("drop or disable")
    }

    // the purge role is a repeatable migration: a new value is a new checksum, so it runs again
    @Test
    fun `changing the purge role and migrating again replaces it`() {
        val core = listOf(ModuleMigration.CORE)
        try {
            WasichaiMigrations(database, schemas, core, WasichaiAuditProperties("another_purger")).migrate()
            owner().use { assertThat(single(it, "SELECT $META.audit_log_purge_role()")).isEqualTo("another_purger") }
            WasichaiMigrations(database, schemas, core, WasichaiAuditProperties()).migrate()
            owner().use { assertThat(single(it, "SELECT $META.audit_log_purge_role()")).isNull() }
        } finally {
            WasichaiMigrations(database, schemas, core, WasichaiAuditProperties(PURGER)).migrate()
        }
        owner().use { assertThat(single(it, "SELECT $META.audit_log_purge_role()")).isEqualTo(PURGER) }
    }

    // ---- jdbc: one connection per role, like an operator's psql

    private fun owner(): Connection = connect(database.username, database.password)

    private fun login(role: String): Connection = connect(role, ROLE_PASSWORD)

    private fun connect(
        user: String,
        password: String
    ): Connection =
        DriverManager.getConnection(
            "jdbc:postgresql://${environment.getProperty("wasichai.database.host")}:${environment.getProperty("wasichai.database.port")}/" +
                environment.getProperty("wasichai.database.name"),
            user,
            password
        )

    // CREATE ROLE has no IF NOT EXISTS; the password is reset every run
    private fun ensureRole(
        connection: Connection,
        role: String
    ) {
        connection.createStatement().use { statement ->
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') THEN CREATE ROLE $role; END IF; END $$")
            statement.execute("ALTER ROLE $role LOGIN PASSWORD '$ROLE_PASSWORD'")
            statement.execute("GRANT USAGE ON SCHEMA $META TO $role")
        }
    }

    private fun insertRow(
        connection: Connection,
        document: UUID? = null
    ): UUID =
        connection
            .prepareStatement(
                "INSERT INTO $META.audit_log (organization_id, object_name, operation, reason, document_id) " +
                    "VALUES (gen_random_uuid(), 'guard_test', 'CREATE', 'written once', ?) RETURNING id"
            ).use { statement ->
                statement.setObject(1, document)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getObject(1, UUID::class.java)
                }
            }

    private fun reasonOf(
        connection: Connection,
        row: UUID
    ): String? = single(connection, "SELECT reason FROM $META.audit_log WHERE id = ?", row)

    private fun count(
        connection: Connection,
        row: UUID? = null
    ): Long = single(connection, "SELECT count(*)::text FROM $META.audit_log" + if (row == null) "" else " WHERE id = '$row'")!!.toLong()

    private fun single(
        connection: Connection,
        sql: String,
        vararg params: Any
    ): String? =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun execute(
        connection: Connection,
        sql: String,
        vararg params: Any
    ): Int =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }

    private fun inTransaction(
        connection: Connection,
        purge: Boolean = false,
        setRole: String? = null,
        commit: Boolean = true,
        block: () -> Unit
    ) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                if (purge) statement.execute("SET LOCAL wasichai.audit.purge = 'on'")
                if (setRole != null) statement.execute("SET LOCAL ROLE $setRole")
            }
            block()
            if (commit) connection.commit() else connection.rollback()
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun sqlState(
        connection: Connection,
        sql: String
    ): String? =
        try {
            connection.createStatement().use { it.execute(sql) }
            null
        } catch (e: SQLException) {
            e.sqlState
        }

    // the error an operator sees: the operation by name, 42501, and the hint
    private fun assertRefused(
        connection: Connection,
        operation: String,
        sql: String,
        purge: Boolean = false,
        setRole: String? = null
    ) {
        try {
            inTransaction(connection, purge, setRole) { connection.createStatement().use { it.execute(sql) } }
            fail<Unit>("$sql went through")
        } catch (e: SQLException) {
            assertThat(e.sqlState).describedAs(sql).isEqualTo(INSUFFICIENT_PRIVILEGE)
            assertThat(e.message).describedAs(sql).contains("audit_log is append-only: $operation is not allowed").contains("ADR-054")
        }
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
