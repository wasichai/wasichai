package wasichai.gis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val BASE = "http://localhost:8081/geoserver"

class GeoServerUrlsTest {
    @Test
    fun `rest urls follow the workspace datastore featuretype hierarchy`() {
        assertThat(GeoServerUrls.workspaces(BASE)).isEqualTo("$BASE/rest/workspaces")
        assertThat(GeoServerUrls.workspace(BASE, "wasichai")).isEqualTo("$BASE/rest/workspaces/wasichai")
        assertThat(GeoServerUrls.dataStores(BASE, "wasichai")).isEqualTo("$BASE/rest/workspaces/wasichai/datastores")
        assertThat(GeoServerUrls.dataStore(BASE, "wasichai", "wasichai-postgis"))
            .isEqualTo("$BASE/rest/workspaces/wasichai/datastores/wasichai-postgis")
        assertThat(GeoServerUrls.featureTypes(BASE, "wasichai", "wasichai-postgis"))
            .isEqualTo("$BASE/rest/workspaces/wasichai/datastores/wasichai-postgis/featuretypes")
        assertThat(GeoServerUrls.featureType(BASE, "wasichai", "wasichai-postgis", "predio__00000000"))
            .isEqualTo("$BASE/rest/workspaces/wasichai/datastores/wasichai-postgis/featuretypes/predio__00000000")
    }

    @Test
    fun `styles live in the workspace, layers are workspace qualified`() {
        assertThat(GeoServerUrls.styles(BASE, "wasichai")).isEqualTo("$BASE/rest/workspaces/wasichai/styles")
        assertThat(GeoServerUrls.style(BASE, "wasichai", "predio__00000000__geom"))
            .isEqualTo("$BASE/rest/workspaces/wasichai/styles/predio__00000000__geom")
        assertThat(GeoServerUrls.layer(BASE, "wasichai", "predio__00000000__geom")).isEqualTo("$BASE/rest/layers/wasichai:predio__00000000__geom")
    }

    @Test
    fun `service urls hang off the workspace, except wmts which is global`() {
        assertThat(GeoServerUrls.wms(BASE, "wasichai")).isEqualTo("$BASE/wasichai/wms")
        assertThat(GeoServerUrls.wfs(BASE, "wasichai")).isEqualTo("$BASE/wasichai/wfs")
        assertThat(GeoServerUrls.wmts(BASE)).isEqualTo("$BASE/gwc/service/wmts")
    }

    @Test
    fun `layer urls qualify the layer with the workspace`() {
        assertThat(GeoServerUrls.wmsLayer(BASE, "wasichai", "predio__00000000"))
            .isEqualTo("$BASE/wasichai/wms?service=WMS&version=1.3.0&request=GetMap&layers=wasichai:predio__00000000")
        assertThat(GeoServerUrls.wfsLayer(BASE, "wasichai", "predio__00000000"))
            .isEqualTo("$BASE/wasichai/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=wasichai:predio__00000000")
    }

    @Test
    fun `a configured trailing slash does not double up`() {
        val properties = GeoServerProperties(url = "http://geoserver:8080/geoserver/")
        assertThat(properties.baseUrl).isEqualTo("http://geoserver:8080/geoserver")
        assertThat(GeoServerUrls.workspaces(properties.baseUrl)).isEqualTo("http://geoserver:8080/geoserver/rest/workspaces")
    }

    @Test
    fun `clients get the public url when one is set, rest calls keep the internal one`() {
        val properties = GeoServerProperties(url = "http://geoserver:8080/geoserver", publicUrl = "/geoserver/")
        assertThat(properties.baseUrl).isEqualTo("http://geoserver:8080/geoserver")
        assertThat(properties.publicBaseUrl).isEqualTo("/geoserver")
        assertThat(GeoServerUrls.wmsLayer(properties.publicBaseUrl, "wasichai", "predio__00000000"))
            .startsWith("/geoserver/wasichai/wms?")
    }

    @Test
    fun `without a public url, or with a blank one, clients get the internal url`() {
        assertThat(GeoServerProperties(url = "http://geoserver:8080/geoserver/").publicBaseUrl).isEqualTo("http://geoserver:8080/geoserver")
        assertThat(GeoServerProperties(url = "http://geoserver:8080/geoserver", publicUrl = " ").publicBaseUrl)
            .isEqualTo("http://geoserver:8080/geoserver")
    }
}
