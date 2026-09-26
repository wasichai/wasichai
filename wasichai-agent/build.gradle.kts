plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai agent: an AI assistant over the caller's own data, on Embabel"

dependencies {
    api(project(":wasichai-core"))
    // AgentService takes embabel's AgentPlatform: part of the api. provider-neutral -- EmbabelGate
    // only ever names the anthropic auto-config by string, never links against it. the default
    // provider (anthropic) is the starter's choice, made in wasichai-spring-boot-starter-agent.
    api(libs.embabel.agent.starter)
    // available_transitions asks workflow when an app has it: compileOnly, guarded by @ConditionalOnClass
    compileOnly(project(":wasichai-workflow"))

    testImplementation(project(":wasichai-test"))
    testImplementation(project(":wasichai-workflow"))
}
