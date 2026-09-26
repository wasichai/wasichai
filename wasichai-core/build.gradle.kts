plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai core: metadata, records, identity, organizations, audit"

dependencies {
    // an app on wasichai-core is a webflux + r2dbc + jwt app. these are part of the api.
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.data.r2dbc)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.oauth2.resource.server)
    api(libs.spring.boot.starter.validation)
    api(libs.jackson.module.kotlin)
    api(libs.kotlinx.coroutines.reactor)
    // flyway runs over jdbc at startup (ADR-008). drivers come with the starter (P2).
    implementation(libs.flyway.core)

    testImplementation(libs.spring.boot.starter.webflux.test)
    // wasichai-test exposes wasichai-core as api: drop it here, or core's own classes and imports land on the test classpath twice
    testImplementation(project(":wasichai-test")) {
        exclude(group = "wasichai", module = "wasichai-core")
    }
    testRuntimeOnly(libs.r2dbc.postgresql)
    testRuntimeOnly(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
