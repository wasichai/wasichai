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
    // the security floors of wasichai.spring-module, for apps that import only this bom: maven never
    // inherits a dependency's dependencyManagement, only an imported bom's. before boot's: in maven the
    // first import that manages an artifact wins (gradle takes the highest version either way)
    api(platform(libs.jackson2.bom))
    api(platform(libs.jackson3.bom))
    api(platform(libs.spring.boot.bom))
    constraints {
        api(libs.scram.client)
        api(libs.scram.common)
        rootProject.subprojects
            .filter { it.name.startsWith("wasichai-") && it.name !in unpublished }
            .forEach { api(project(it.path)) }
    }
}
