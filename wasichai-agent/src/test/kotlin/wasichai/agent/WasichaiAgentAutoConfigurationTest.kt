package wasichai.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.test.util.ReflectionTestUtils
import wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.util.UUID

class WasichaiAgentAutoConfigurationTest {
    // no embabel auto-config here: exactly the no-key case, where the gate keeps it out
    private val runner = WasichaiContextRunner.core().withConfiguration(AutoConfigurations.of(WasichaiAgentAutoConfiguration::class.java))

    @Test
    fun `the agent wires without embabel's platform and says it is unavailable`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(AgentController::class.java)
            assertThat(context).hasSingleBean(WasichaiAgent::class.java)
            assertThat(context.getBean(AgentService::class.java).status().enabled).isFalse()
        }
    }

    @Test
    fun `a key makes it available`() {
        runner.withPropertyValues("wasichai.agent.api-key=k").run { context ->
            assertThat(context.getBean(AgentService::class.java).status().enabled).isTrue()
        }
    }

    @Test
    fun `without a workflow module, transitions come from the null port`() {
        runner.run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions")).isInstanceOf(NoRecordTransitions::class.java)
        }
    }

    @Test
    fun `a transitions port next to it is used`() {
        val port = RecordTransitions { _: String, _: UUID -> listOf(AgentTransition("finish", "Finish", "done", "Done", true)) }
        runner.withBean(RecordTransitions::class.java, { port }).run { context ->
            assertThat(ReflectionTestUtils.getField(context.getBean(AgentTools::class.java), "transitions")).isSameAs(port)
        }
    }

    @Test
    fun `switched off, no agent route exists`() {
        runner.withPropertyValues("wasichai.agent.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(AgentService::class.java)
            assertThat(context).doesNotHaveBean(AgentController::class.java)
        }
    }

    @Test
    fun `the auto-config and the gate are registered`() {
        assertThat(ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates)
            .contains("wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration")
        // name check only: instantiating every EPP on the classpath throws on Boot's own ones,
        // which need constructor args a plain loader can't supply.
        val registered =
            javaClass.classLoader
                .getResources("META-INF/spring.factories")
                .toList()
                .map { it.readText() }
        assertThat(registered).anyMatch {
            it.contains("org.springframework.boot.EnvironmentPostProcessor") && it.contains("wasichai.agent.autoconfigure.EmbabelGate")
        }
    }
}
