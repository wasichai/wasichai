plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

// wasichai-pages brings wasichai-forms with it
description = "Wasichai pages starter: the Wasichai starter plus wasichai-pages (and wasichai-forms)"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-pages"))
}
