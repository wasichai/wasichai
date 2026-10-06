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

    testImplementation(project(":wasichai-test"))
    testRuntimeOnly(libs.r2dbc.postgresql)
}
