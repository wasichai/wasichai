package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import wasichai.core.data.RecordQueryParser
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.metadata.ObjectRemovalListener
import wasichai.core.platform.ModuleMigration
import wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiGisAutoConfigurationTest {
    private val runner = WasichaiContextRunner.core().withConfiguration(AutoConfigurations.of(WasichaiGisAutoConfiguration::class.java))

    @Test
    fun `gis plugs GEOMETRY, bbox and layer cleanup into core`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val registry = context.getBean(FieldTypeRegistry::class.java)
            assertThat(registry.types.last()).isEqualTo(GEOMETRY)
            assertThat(registry.sections).containsExactly("geometries")
            val query = context.getBean(RecordQueryParser::class.java).parse(mapOf("bbox" to "1,2,3,4", "codigo" to "A-1"))
            assertThat(query.filters).containsOnlyKeys("codigo")
            assertThat(query.criteria).hasSize(1)
            assertThat(context.getBeansOfType(ObjectRemovalListener::class.java).values).hasAtLeastOneElementOfType(LayerCleanup::class.java)
            assertThat(context).hasSingleBean(FeatureController::class.java)
            assertThat(context).hasSingleBean(LayerController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactlyInAnyOrder("core", "gis")
        }
    }

    @Test
    fun `geoserver settings bind under wasichai gis geoserver`() {
        runner.withPropertyValues("wasichai.gis.geoserver.url=http://gs:8080/geoserver/", "wasichai.gis.geoserver.enabled=false").run { context ->
            val properties = context.getBean(GeoServerProperties::class.java)
            assertThat(properties.baseUrl).isEqualTo("http://gs:8080/geoserver")
            assertThat(properties.enabled).isFalse()
            // unset: the geoserver client falls back to wasichai.database.data-schema, not a literal default
            assertThat(properties.datastore.schema).isNull()
            assertThat(properties.workspace).isEqualTo("wasichai")
        }
    }

    // an app that only sets wasichai.database.data-schema still gets a working layer: no separate
    // geoserver override is required
    @Test
    fun `data-schema flows to geoserver when no datastore override is set`() {
        runner.withPropertyValues("wasichai.database.data-schema=acme_data").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(GeoServerClient::class.java).schema).isEqualTo("acme_data")
        }
    }

    // an explicit datastore override wins over wasichai.database.data-schema: geoserver may read a
    // different schema than the one wasichai itself writes to
    @Test
    fun `a datastore schema override wins over data-schema`() {
        runner
            .withPropertyValues("wasichai.database.data-schema=acme_data", "wasichai.gis.geoserver.datastore.schema=layers_src")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(GeoServerClient::class.java).schema).isEqualTo("layers_src")
            }
    }

    // the core-only answer: no GEOMETRY, and ?bbox= is just an unknown field filter (P1 R7)
    @Test
    fun `switched off, core knows nothing of geometry`() {
        runner.withPropertyValues("wasichai.gis.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(FieldTypeRegistry::class.java).isInstalled(GEOMETRY)).isFalse()
            assertThat(context.getBean(RecordQueryParser::class.java).parse(mapOf("bbox" to "1,2,3,4")).filters).containsOnlyKeys("bbox")
            assertThat(context).doesNotHaveBean(FeatureController::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).containsExactly("core")
        }
    }

    @Test
    fun `the imports file registers the auto-config`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.gis.autoconfigure.WasichaiGisAutoConfiguration")
    }

    // infra-agnostic (ADR-031 D17): geoserver reaches postgres at localhost unless the app says otherwise.
    // a container network (compose's "postgres") is the app's setting, not the library's default
    @Test
    fun `the geoserver datastore defaults name no container network`() {
        runner.run { context ->
            val datastore = context.getBean(GeoServerProperties::class.java).datastore
            assertThat(datastore.host).isEqualTo("localhost")
            assertThat(datastore.port).isEqualTo(5432)
            assertThat(datastore.database).isEqualTo("wasichai")
        }
    }

    @Test
    fun `a container network sets the datastore host`() {
        runner.withPropertyValues("wasichai.gis.geoserver.datastore.host=postgres").run { context ->
            assertThat(context.getBean(GeoServerProperties::class.java).datastore.host).isEqualTo("postgres")
        }
    }
}
