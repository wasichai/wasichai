package wasichai.it.full

import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest
import java.lang.annotation.Inherited

// the full app's test properties, in one place: FullAppBootTest cannot extend FullAppIntegrationTest
// (it reuses the slice checks) but must boot the same context. no background drain: the tests drive
// the automation runner by hand, as the original's did. no notifications loop either: its purge and rules
// run across every organization of the shared database, under other tests' feet; a test drives it with
// runSource. geoserver stays out of the suite; a real publish is checked by hand.
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Inherited
@TestPropertySource(
    properties = [
        "wasichai.automation.poll-interval=0s",
        "wasichai.notifications.tick=0s",
        "wasichai.gis.geoserver.enabled=false",
        "wasichai.gis.geoserver.url=http://geoserver.invalid:8081/geoserver"
    ]
)
annotation class FullAppProperties

// base of every ported api test
@FullAppProperties
abstract class FullAppIntegrationTest : WasichaiIntegrationTest()
