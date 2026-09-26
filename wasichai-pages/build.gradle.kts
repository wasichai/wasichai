plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai pages: record pages as a component tree on a template"

dependencies {
    api(project(":wasichai-core"))
    // a FORM component names a stored form: the one hard module-to-module edge (spec module graph)
    api(project(":wasichai-forms"))

    testImplementation(project(":wasichai-test"))
}
