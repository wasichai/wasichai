plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

// the S3-compatible store needs software.amazon.awssdk:s3 on the app's classpath: not brought here
description = "Wasichai files starter: the Wasichai starter plus wasichai-files"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-files"))
}
