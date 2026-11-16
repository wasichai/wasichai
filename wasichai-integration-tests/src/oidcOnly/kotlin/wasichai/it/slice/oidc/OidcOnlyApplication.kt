package wasichai.it.slice.oidc

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// core alone, in oidc mode
@SpringBootConfiguration
@EnableAutoConfiguration
class OidcOnlyApplication {
    @Bean
    fun probeController(): ProbeController = ProbeController()
}

// an app endpoint that never calls CurrentUser.require(): a denied realm token must still not get through
@RestController
class ProbeController {
    @GetMapping("/api/probe")
    fun probe(): Map<String, String> = mapOf("reached" to "yes")
}
