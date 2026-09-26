package wasichai.test

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

// the five WASICHAI_TEST_DB_* vars, once WASICHAI_TEST_DB_HOST says "use an external database".
private const val ENV_HOST = "WASICHAI_TEST_DB_HOST"
private const val ENV_PORT = "WASICHAI_TEST_DB_PORT"
private const val ENV_NAME = "WASICHAI_TEST_DB_NAME"
private const val ENV_USERNAME = "WASICHAI_TEST_DB_USERNAME"
private const val ENV_PASSWORD = "WASICHAI_TEST_DB_PASSWORD"

// pulled out of the object so a test can pass a fake lookup instead of mutating the real process env.
// WASICHAI_TEST_DB_HOST alone used to mean "external db, default whatever else is missing" - a missing
// WASICHAI_TEST_DB_PORT silently fell back to 5432, which can point the destructive wipe at the wrong
// server. once the host is set, every other var is required: no silent defaults for a wipe target.
internal fun resolveExternalDatabaseConfig(env: (String) -> String?): Map<String, String>? {
    val host = env(ENV_HOST)?.takeIf { it.isNotBlank() } ?: return null
    val required = linkedMapOf(ENV_PORT to env(ENV_PORT), ENV_NAME to env(ENV_NAME), ENV_USERNAME to env(ENV_USERNAME), ENV_PASSWORD to env(ENV_PASSWORD))
    val missing = required.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) {
        "$ENV_HOST is set but missing ${missing.joinToString(", ")}: external-db mode needs all five " +
            "WASICHAI_TEST_DB_* vars, there is no safe default for a wipe target"
    }
    return mapOf(
        ENV_HOST to host,
        ENV_PORT to required.getValue(ENV_PORT)!!,
        ENV_NAME to required.getValue(ENV_NAME)!!,
        ENV_USERNAME to required.getValue(ENV_USERNAME)!!,
        ENV_PASSWORD to required.getValue(ENV_PASSWORD)!!
    )
}

// the one database every integration test in a jvm shares. testcontainers by default. when
// WASICHAI_TEST_DB_HOST is set, an already running database instead: a remote docker daemon publishes
// container ports on its own host, out of reach here.
object WasichaiTestDatabase {
    const val DEFAULT_IMAGE = "postgres:18"
    const val POSTGIS_IMAGE = "postgis/postgis:18-3.6"

    // core runs on plain postgres. a module that needs postgis sets the image for its test task:
    // tasks.integrationTest { systemProperty("wasichai.test.db.image", "postgis/postgis:18-3.6") }
    val image: String = System.getProperty("wasichai.test.db.image") ?: System.getenv("WASICHAI_TEST_DB_IMAGE") ?: DEFAULT_IMAGE

    // validated once, at class init: a partial external config is a config error, caught immediately
    // instead of surfacing later as "wiped the wrong database"
    private val externalConfig: Map<String, String>? = resolveExternalDatabaseConfig(System::getenv)

    private val container: PostgreSQLContainer? by lazy {
        if (externalConfig != null) {
            null
        } else {
            PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("wasichai")
                .withUsername("wasichai")
                .withPassword("wasichai")
                .also { it.start() }
        }
    }

    // an external database outlives the suite: take the suite lock, then wipe it once per jvm,
    // before the first context
    private val ready: Boolean by lazy {
        externalConfig?.let {
            lockSuite(it)
            wipeExternalDatabase(it)
        }
        true
    }

    /** The advisory lock a jvm holds on an external test database from its wipe until it exits. */
    const val SUITE_LOCK = "wasichai-test-suite"

    // long enough for a queue of whole suites ahead of us, short of forever
    private const val SUITE_LOCK_WAIT = "60min"
    private const val LOCK_NOT_AVAILABLE = "55P03"

    // never closed by us: closing it is what releases the lock, and that happens when the jvm exits
    @Volatile
    private var suiteLockConnection: Connection? = null

    /**
     * Two suites against one external database used to wipe each other mid-run. The second one now
     * waits here, on a session advisory lock taken on a connection of its own and held until this
     * jvm exits. Postgres drops the lock with the connection, so a killed run never leaves it stuck.
     * Another database (another port) has its own lock: a plain and a postgis suite still overlap.
     */
    private fun lockSuite(config: Map<String, String>) {
        // already held by this jvm: a second pg_advisory_lock on a new connection would wait on ourselves
        if (suiteLockConnection != null) return
        val connection = connect(config)
        val target = "${config.getValue(ENV_NAME)} on ${config.getValue(ENV_HOST)}:${config.getValue(ENV_PORT)}"
        try {
            connection.createStatement().use { statement ->
                val free =
                    statement.executeQuery("SELECT pg_try_advisory_lock(hashtext('$SUITE_LOCK'))").use { rows -> rows.next() && rows.getBoolean(1) }
                if (!free) {
                    val holders = advisoryLockHolders(connection)
                    System.err.println("wasichai-test: another suite holds $target ($holders); waiting up to $SUITE_LOCK_WAIT for it to finish")
                    // bounded: a holder paused in a debugger must not block every later suite silently
                    statement.execute("SET lock_timeout = '$SUITE_LOCK_WAIT'")
                    try {
                        statement.execute("SELECT pg_advisory_lock(hashtext('$SUITE_LOCK'))")
                    } catch (e: SQLException) {
                        if (e.sqlState != LOCK_NOT_AVAILABLE) throw e
                        throw IllegalStateException(
                            "wasichai-test: waited $SUITE_LOCK_WAIT for the suite lock on $target, still held by ${advisoryLockHolders(connection)}",
                            e
                        )
                    }
                    statement.execute("RESET lock_timeout")
                }
            }
        } catch (e: Throwable) {
            // no lock held: nothing may keep this connection open
            runCatching { connection.close() }
            throw e
        }
        suiteLockConnection = connection
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { connection.close() } })
    }

    // who to blame in the message; any advisory lock on this database, the suite lock among them
    private fun advisoryLockHolders(connection: Connection): String =
        runCatching {
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT DISTINCT a.pid, a.application_name, a.client_addr FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid " +
                            "WHERE l.locktype = 'advisory' AND l.granted AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database()) " +
                            "AND l.pid <> pg_backend_pid()"
                    ).use { rows ->
                        buildList { while (rows.next()) add("pid ${rows.getInt(1)} '${rows.getString(2)}' from ${rows.getString(3)}") }
                    }
            }
        }.map { if (it.isEmpty()) "holder unknown" else it.joinToString(", ") }
            .getOrElse { "holder unknown: ${it.message}" }

    private fun connect(config: Map<String, String>): Connection {
        val name = requireTestDatabaseName(config.getValue(ENV_NAME))
        return DriverManager.getConnection(
            "jdbc:postgresql://${config.getValue(ENV_HOST)}:${config.getValue(ENV_PORT)}/$name",
            config.getValue(ENV_USERNAME),
            config.getValue(ENV_PASSWORD)
        )
    }

    fun properties(): Map<String, String> {
        check(ready)
        val running = container
        return if (running == null) {
            val config = externalConfig!!
            mapOf(
                "wasichai.database.host" to config.getValue(ENV_HOST),
                "wasichai.database.port" to config.getValue(ENV_PORT),
                "wasichai.database.name" to config.getValue(ENV_NAME),
                "wasichai.database.username" to config.getValue(ENV_USERNAME),
                "wasichai.database.password" to config.getValue(ENV_PASSWORD)
            )
        } else {
            mapOf(
                "wasichai.database.host" to running.host,
                "wasichai.database.port" to running.firstMappedPort.toString(),
                "wasichai.database.name" to running.databaseName,
                "wasichai.database.username" to running.username,
                "wasichai.database.password" to running.password
            )
        }
    }

    // the one thing that must never be wrong here
    fun requireTestDatabaseName(name: String): String {
        require(name.endsWith("_test")) { "refusing to wipe '$name': a test database's name has to end in _test" }
        return name
    }

    fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * One DROP per statement, never one big transaction: postgres takes a lock per table and dies with
     * "out of shared memory" long before a thousand of them. autocommit gives each drop its own.
     * Every user schema goes (a test may use its own schema names); tables an extension owns stay,
     * and so does any schema an extension lives in or created. Flyway history goes too, so migrations replay.
     * Two suites against the same external database no longer collide: the suite lock ([lockSuite])
     * makes the second jvm wait until the first exits, then it wipes and runs.
     *
     * `public` is never touched: it is excluded from "user schemas" like `information_schema` and the
     * `pg_%` schemas are. A metadata or data schema configured as `public` is therefore NOT cleaned
     * between runs - keep schema names out of `public` in tests that rely on this wipe.
     */
    private fun wipeExternalDatabase(config: Map<String, String>) {
        val host = config.getValue(ENV_HOST)
        val name = requireTestDatabaseName(config.getValue(ENV_NAME))
        val port = config.getValue(ENV_PORT)
        val user = config.getValue(ENV_USERNAME)
        val password = config.getValue(ENV_PASSWORD)
        DriverManager.getConnection("jdbc:postgresql://$host:$port/$name", user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SELECT pg_advisory_lock(hashtext('wasichai-test-wipe'))")
                val userSchemas = "n.nspname NOT LIKE 'pg\\_%' AND n.nspname NOT IN ('information_schema', 'public')"
                val tables = mutableListOf<Pair<String, String>>()
                statement
                    .executeQuery(
                        """
                        SELECT n.nspname, c.relname
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE $userSchemas
                          AND c.relkind IN ('r', 'p')
                          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
                        """.trimIndent()
                    ).use { rows -> while (rows.next()) tables.add(rows.getString(1) to rows.getString(2)) }
                tables.forEach { (schema, table) ->
                    statement.execute("DROP TABLE IF EXISTS ${quoteIdentifier(schema)}.${quoteIdentifier(table)} CASCADE")
                }
                val schemas = mutableListOf<String>()
                statement
                    .executeQuery(
                        "SELECT n.nspname FROM pg_namespace n WHERE $userSchemas " +
                            "AND NOT EXISTS (SELECT 1 FROM pg_extension e WHERE e.extnamespace = n.oid) " +
                            // a schema an extension created (postgis_tiger_geocoder's tiger_data) belongs to it
                            "AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_namespace'::regclass AND d.objid = n.oid AND d.deptype = 'e')"
                    ).use { rows -> while (rows.next()) schemas.add(rows.getString(1)) }
                schemas.forEach { statement.execute("DROP SCHEMA IF EXISTS ${quoteIdentifier(it)} CASCADE") }
                statement.execute("SELECT pg_advisory_unlock(hashtext('wasichai-test-wipe'))")
            }
        }
    }
}
