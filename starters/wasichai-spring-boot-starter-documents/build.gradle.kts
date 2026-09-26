plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

// automation can issue documents when an app adds wasichai-spring-boot-starter-automation too
description = "Wasichai documents starter: the Wasichai starter plus wasichai-documents"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-documents"))
}
