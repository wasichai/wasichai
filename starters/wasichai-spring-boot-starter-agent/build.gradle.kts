plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

// no ANTHROPIC_API_KEY, no assistant: the app still boots (EmbabelGate)
description = "Wasichai agent starter: the Wasichai starter plus wasichai-agent"

dependencies {
    api(project(":wasichai-spring-boot-starter"))
    api(project(":wasichai-agent"))
    // wasichai-agent is provider-neutral; the starter picks the original app's default provider
    api(libs.embabel.agent.starter.anthropic)
}
