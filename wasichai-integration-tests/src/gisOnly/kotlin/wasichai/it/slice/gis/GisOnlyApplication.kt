package wasichai.it.slice.gis

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + gis, nothing else on the classpath (no pages: no MAP component)
@SpringBootConfiguration
@EnableAutoConfiguration
class GisOnlyApplication
