# wasichai-spring-boot-starter-agent

The wasichai starter plus `wasichai-agent`, the AI assistant, with Anthropic as the model provider.

`wasichai-agent` itself is provider-neutral: it depends only on `embabel-agent-starter`. This starter adds
`embabel-agent-starter-anthropic`, the provider the original app always used, so the assistant works as soon as an
Anthropic API key is configured. Without a key the app still boots and the assistant reports itself unavailable.

To use another provider, depend on `wasichai:wasichai-agent` plus that provider's Embabel starter instead of this
starter, or keep this starter and exclude the Anthropic provider:

    implementation("wasichai:wasichai-spring-boot-starter-agent") {
        exclude(group = "com.embabel.agent", module = "embabel-agent-starter-anthropic")
    }

See [docs/modules/agent.md](../../docs/modules/agent.md).
