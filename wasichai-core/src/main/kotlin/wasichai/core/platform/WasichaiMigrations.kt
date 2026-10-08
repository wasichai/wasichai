package wasichai.core.platform

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.InitializingBean

// runs every module's migrations at startup, before traffic, in order. flyway is jdbc only,
// so it gets its own short-lived connection. ADR-008, ADR-0026.
//
// plan order is (order, name): ties break alphabetically, not by dependency. a module whose
// migration reads another module's tables must pick a strictly higher `order` than that module,
// not just rely on its name sorting later (see ModuleMigration.MODULE_ORDER).
//
// runs via InitializingBean, not @Bean(initMethod = "migrate"): an app that replaces the
// wasichaiMigrations bean with its own WasichaiMigrations instance still gets afterPropertiesSet()
// called by the container, so migrations still run either way. a module bean that touches the
// database at init must declare @DependsOn("wasichaiMigrations") so it does not race the migration
// that creates its tables — keep that bean name stable.
//
// audit: the purge role core's R__audit_purge_role.sql writes (ADR-054).
class WasichaiMigrations(
    private val database: WasichaiDatabaseProperties,
    private val schemas: WasichaiSchemas,
    migrations: List<ModuleMigration>,
    private val audit: WasichaiAuditProperties
) : InitializingBean {
    // the form an app compiled against before the purge role: no purge role
    constructor(
        database: WasichaiDatabaseProperties,
        schemas: WasichaiSchemas,
        migrations: List<ModuleMigration>
    ) : this(database, schemas, migrations, WasichaiAuditProperties())

    val plan: List<ModuleMigration> = migrations.sortedWith(compareBy({ it.order }, { it.name }))

    init {
        val twice = plan.groupBy { it.name }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "module migration registered twice: ${twice.joinToString(", ")}" }
    }

    override fun afterPropertiesSet() = migrate()

    fun migrate() {
        plan.forEach { flyway(it).migrate() }
    }

    internal fun flyway(module: ModuleMigration): Flyway =
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .schemas(schemas.metadata)
            .defaultSchema(schemas.metadata)
            .table(module.historyTable)
            .locations(module.location)
            // every module after the first finds the schema in use; baseline 0 still runs its V1
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .placeholders(
                mapOf("metadataSchema" to schemas.metadata, "dataSchema" to schemas.data, "auditPurgeRole" to audit.purgeRolePlaceholder)
            ).load()
}
