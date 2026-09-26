plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai views starter: the Wasichai starter plus wasichai-views"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-views"))
}
