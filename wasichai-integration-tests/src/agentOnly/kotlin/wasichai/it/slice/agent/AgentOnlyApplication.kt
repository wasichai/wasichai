package wasichai.it.slice.agent

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + agent, nothing else on the classpath (no workflow: no transitions to offer)
@SpringBootConfiguration
@EnableAutoConfiguration
class AgentOnlyApplication
