plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai automation starter: the Wasichai starter plus wasichai-automation"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-automation"))
}
