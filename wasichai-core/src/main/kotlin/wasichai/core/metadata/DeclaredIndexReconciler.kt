package wasichai.core.metadata

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import wasichai.core.platform.TenantDirectory

// tables built before ADR-036 have no index on their relation columns, and an index can go missing
// by hand. at every start, every organization's tables get what their metadata declares and the
// catalog lacks: one catalog read, then only the missing CREATE INDEX IF NOT EXISTS. never drops.
// runs once the app is ready, so a context without a database (a slice test) never reaches it.
// the tenants come from the TenantDirectory, the one iteration over organizations (ADR-057).
class DeclaredIndexReconciler(
    private val tenants: TenantDirectory,
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository,
    private val schema: ObjectSchemaManager
) : ApplicationListener<ApplicationReadyEvent> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        runBlocking { reconcile() }
    }

    // a failure is logged per object, not thrown: two instances starting together race on the same
    // CREATE INDEX, and a missing index is slower reads, not a reason to refuse to start
    suspend fun reconcile(): Int {
        val all = tenants.organizations().flatMap { objects.findAll(it.id) }
        if (all.isEmpty()) return 0
        val byObject = fields.findByObjects(all.map { it.id })
        val existing = schema.indexNames()
        var created = 0
        all.forEach { obj ->
            try {
                created += schema.ensureIndexes(ObjectDefinition(obj, byObject[obj.id].orEmpty()), existing)
            } catch (e: Exception) {
                log.warn("could not build the declared indexes of {} ({}): {}", obj.name, obj.physicalTable, e.message)
            }
        }
        if (created > 0) log.info("built {} declared indexes missing from the data tables", created)
        return created
    }
}
