plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai workflow starter: the Wasichai starter plus wasichai-workflow"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-workflow"))
}
