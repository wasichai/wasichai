plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

// needs a PostgreSQL server with the postgis extension available
description = "Wasichai GIS starter: the Wasichai starter plus wasichai-gis"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-gis"))
}
