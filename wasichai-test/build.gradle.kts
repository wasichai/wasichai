plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
}

description = "Wasichai test fixtures: an integration-test base on a real PostgreSQL"

dependencies {
    api(libs.spring.boot.starter.test)
    api(libs.spring.boot.starter.webflux.test)
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.junit)
    api(libs.testcontainers.postgresql)
    // the external-database wipe talks plain jdbc
    implementation(libs.postgresql.jdbc)
    // WasichaiContextRunner names core's auto-configs, so the pom must say which core it was built against
    api(project(":wasichai-core"))
}
