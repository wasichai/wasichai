package wasichai.it.slice.pages

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// core + pages (+ forms, its one hard dependency), nothing else on the classpath
@SpringBootConfiguration
@EnableAutoConfiguration
class PagesOnlyApplication
