plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai workflow: record states and transitions per object"

dependencies {
    api(project(":wasichai-core"))
    // the WORKFLOW page component, only when an app has wasichai-pages: compileOnly + @ConditionalOnClass
    compileOnly(project(":wasichai-pages"))

    testImplementation(project(":wasichai-test"))
    testImplementation(project(":wasichai-pages"))
}
