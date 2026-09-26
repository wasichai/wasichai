package wasichai.core

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// the app the api tests boot: auto-configuration and nothing else, the way a real app gets wasichai.
// no component scan, so the library's classes on the test classpath are never picked up twice.
@SpringBootConfiguration
@EnableAutoConfiguration
class WasichaiCoreTestApplication
