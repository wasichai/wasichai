plugins {
    `java-platform`
    id("wasichai.publishing")
}

javaPlatform {
    allowDependencies()
}

// never published: the bom itself and the test suite
// rule this filter assumes: every other wasichai-* project applies wasichai.publishing
val unpublished = setOf("wasichai-bom", "wasichai-integration-tests")

dependencies {
    api(platform(libs.spring.boot.bom))
    // same security floors as wasichai.kotlin-library, for apps that import only this bom
    api(platform(libs.jackson.bom))
    api(platform(libs.jackson2.bom))
    constraints {
        api(libs.scram.client)
        api(libs.scram.common)
        rootProject.subprojects
            .filter { it.name.startsWith("wasichai-") && it.name !in unpublished }
            .forEach { api(project(it.path)) }
    }
}
