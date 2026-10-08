package wasichai.agent

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.core.annotation.Order
import org.springframework.test.util.ReflectionTestUtils
import wasichai.agent.autoconfigure.WasichaiAgentAutoConfiguration
import wasichai.core.identity.AuthenticatedUser
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
            assertThat(runBlocking(TestCallers.context()) { context.getBean(AgentService::class.java).status() }.enabled).isFalse()
        }
    }

    @Test
    fun `a key makes it available`() {
        runner.withPropertyValues("wasichai.agent.api-key=k").run { context ->
            assertThat(runBlocking(TestCallers.context()) { context.getBean(AgentService::class.java).status() }.enabled).isTrue()
        }
    }

    @Test
    fun `with no extension declared, everyone may ask and nothing filters or listens`() {
        runner.run { context ->
            assertThat(context.getBean(AgentAccessPolicy::class.java)).isSameAs(AgentAccessPolicy.ALLOW_ALL)
            val service = context.getBean(AgentService::class.java)
            listOf("resultFilters", "answerFilters", "listeners").forEach { field ->
                assertThat(ReflectionTestUtils.getField(service, field) as List<*>).describedAs(field).isEmpty()
            }
        }
    }

    @Test
    fun `the app's policy replaces the default and its filters and listeners are all used, in order`() {
        val policy = AgentAccessPolicy { AgentAccess.Denied("off") }
        val first = FirstFilter()
        val second = SecondFilter()
        val restore = AgentAnswerFilter { _, text -> text }
        val listener = AgentRunListener { _, _, _, _, _ -> }
        runner
            .withBean(AgentAccessPolicy::class.java, { policy })
            .withBean("second", SecondFilter::class.java, { second })
            .withBean("first", FirstFilter::class.java, { first })
            .withBean(AgentAnswerFilter::class.java, { restore })
            .withBean(AgentRunListener::class.java, { listener })
            .run { context ->
                assertThat(context).hasSingleBean(AgentAccessPolicy::class.java)
                val service = context.getBean(AgentService::class.java)
                assertThat(ReflectionTestUtils.getField(service, "policy")).isSameAs(policy)
                assertThat(ReflectionTestUtils.getField(service, "resultFilters") as List<*>).containsExactly(first, second)
                assertThat(ReflectionTestUtils.getField(service, "answerFilters") as List<*>).containsExactly(restore)
                assertThat(ReflectionTestUtils.getField(service, "listeners") as List<*>).containsExactly(listener)
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

    // declared second, ordered first: @Order decides, not registration
    @Order(1)
    class FirstFilter : AgentResultFilter {
        override suspend fun filter(
            caller: AuthenticatedUser,
            tool: String,
            input: Map<String, Any?>,
            resultJson: String
        ): String = resultJson
    }

    @Order(2)
    class SecondFilter : AgentResultFilter {
        override suspend fun filter(
            caller: AuthenticatedUser,
            tool: String,
            input: Map<String, Any?>,
            resultJson: String
        ): String = resultJson
    }
}
