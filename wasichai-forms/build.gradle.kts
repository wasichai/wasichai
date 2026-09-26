plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai forms: named, sectioned forms per object"

dependencies {
    api(project(":wasichai-core"))

    testImplementation(project(":wasichai-test"))
}
