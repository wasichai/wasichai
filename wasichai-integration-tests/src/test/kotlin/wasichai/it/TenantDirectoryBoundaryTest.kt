package wasichai.it

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import wasichai.core.platform.TenantDirectory

// ADR-057: the tenant list is for background work. no controller of core or of any module takes it,
// as a constructor parameter or a field (generic ones included: ObjectProvider<TenantDirectory>).
class TenantDirectoryBoundaryTest {
    private fun controllers(): List<Class<*>> {
        // @RestController is a @Controller: one filter finds both
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AnnotationTypeFilter(Controller::class.java)) }
        return scanner
            .findCandidateComponents("wasichai")
            .map { Class.forName(it.beanClassName) }
            .filterNot { it.name.startsWith("wasichai.it.") }
            .sortedBy { it.name }
    }

    @Test
    fun `the scan finds the controllers of core and the modules`() {
        val packages = controllers().map { it.packageName.split('.')[1] }.toSet()
        assertThat(packages).contains("core", "views", "forms", "pages", "workflow", "automation", "documents", "gis", "agent", "notifications")
    }

    @Test
    fun `no controller depends on the TenantDirectory`() {
        assertThat(controllers().filter(::takesDirectory).map { it.name }).isEmpty()
    }

    @Test
    fun `the check sees a direct and a wrapped dependency`() {
        assertThat(takesDirectory(Direct::class.java)).isTrue()
        assertThat(takesDirectory(Wrapped::class.java)).isTrue()
        assertThat(takesDirectory(TenantDirectoryBoundaryTest::class.java)).isFalse()
    }

    private fun takesDirectory(type: Class<*>): Boolean {
        val name = TenantDirectory::class.java.name
        val parameters = type.declaredConstructors.flatMap { it.genericParameterTypes.toList() }
        val fields = type.declaredFields.map { it.genericType }
        return (parameters + fields).any { name in it.typeName }
    }

    private class Direct(
        @Suppress("unused") val tenants: TenantDirectory
    )

    private class Wrapped(
        @Suppress("unused") val tenants: ObjectProvider<TenantDirectory>
    )
}
