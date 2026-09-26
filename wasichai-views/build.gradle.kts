plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai views: named list views per object"

dependencies {
    api(project(":wasichai-core"))

    testImplementation(project(":wasichai-test"))
}
