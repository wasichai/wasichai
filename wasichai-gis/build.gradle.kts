plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai GIS: the GEOMETRY field type on PostGIS, bbox queries, features and GeoServer layers"

dependencies {
    api(project(":wasichai-core"))
    // the MAP page component, only when an app has wasichai-pages: compileOnly + @ConditionalOnClass
    compileOnly(project(":wasichai-pages"))

    testImplementation(project(":wasichai-test"))
    testImplementation(project(":wasichai-pages"))
}
