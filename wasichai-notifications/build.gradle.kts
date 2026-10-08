plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai notifications: alerts for people, roles and units, scheduled sources, date rules, live over SSE"

dependencies {
    api(project(":wasichai-core"))
    // LISTEN needs the driver's own connection type. the app brings the driver (the starter does).
    compileOnly(libs.r2dbc.postgresql)
    // the email channel (ADR-060): the app brings spring mail (spring-boot-starter-mail) when it wants email.
    // automation's NOTIFY port: implemented here, loaded only when an app has automation (ADR-024, M2)
    compileOnly(libs.spring.boot.mail)
    compileOnly(project(":wasichai-automation"))

    testImplementation(project(":wasichai-test"))
    testImplementation(libs.spring.boot.mail)
    testImplementation(project(":wasichai-automation"))
    testRuntimeOnly(libs.r2dbc.postgresql)
    // flyway migrates over jdbc; the storage test also LISTENs over a plain jdbc connection
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
