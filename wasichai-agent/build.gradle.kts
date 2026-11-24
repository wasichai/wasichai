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
    // the floor over the spring ai that embabel brings (springAi in the catalog): a security fix, not a feature
    api(platform(libs.spring.ai.bom))
    // available_transitions asks workflow when an app has it: compileOnly, guarded by @ConditionalOnClass
    compileOnly(project(":wasichai-workflow"))

    testImplementation(project(":wasichai-test"))
    testImplementation(project(":wasichai-workflow"))
}
