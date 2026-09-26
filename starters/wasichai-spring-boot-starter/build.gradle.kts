plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai starter: wasichai-core plus the PostgreSQL drivers, Flyway support and actuator an app runs on"

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":wasichai-core"))
    // runtime only: an app never codes against the drivers. r2dbc for records, jdbc + flyway for migrations (ADR-008)
    runtimeOnly(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.jdbc)
    runtimeOnly(libs.flyway.postgresql)
    // /actuator/health is already public in core's security chain
    implementation(libs.spring.boot.starter.actuator)
}
