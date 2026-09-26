plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai automation: trigger -> conditions -> actions on record changes"

dependencies {
    api(project(":wasichai-core"))

    testImplementation(project(":wasichai-test"))
}
