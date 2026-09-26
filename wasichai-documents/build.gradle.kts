plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai documents: document types, templates and issued, numbered documents"

dependencies {
    api(project(":wasichai-core"))
    // the automation port is implemented here, but an app without automation must not get it:
    // compileOnly, and the adapter's auto-config checks the class is there
    compileOnly(project(":wasichai-automation"))

    testImplementation(project(":wasichai-test"))
    testImplementation(project(":wasichai-automation"))
}
