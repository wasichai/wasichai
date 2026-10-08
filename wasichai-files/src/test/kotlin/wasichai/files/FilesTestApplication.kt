package wasichai.files

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration

// the app the integration tests boot: core plus this module, through auto-configuration only.
// no component scan, so the library's classes are never picked up twice.
@SpringBootConfiguration
@EnableAutoConfiguration
class FilesTestApplication
