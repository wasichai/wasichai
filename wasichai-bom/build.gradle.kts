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
    constraints {
        rootProject.subprojects
            .filter { it.name.startsWith("wasichai-") && it.name !in unpublished }
            .forEach { api(project(it.path)) }
    }
}
