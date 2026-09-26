plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai forms starter: the Wasichai starter plus wasichai-forms"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-forms"))
}
