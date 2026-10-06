plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai notifications starter: the Wasichai starter plus wasichai-notifications"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-notifications"))
}
