package wasichai.core.autoconfigure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.core.annotation.MergedAnnotations

// review P2-T1 minor: exclude/excludeName/scanBasePackages must resolve through
// AnnotatedElementUtils/MergedAnnotations, the way Spring itself reads a composed annotation --
// not just be declared. Sample carries no @SpringBootTest, so it cannot be picked up by another
// test's recursive @SpringBootConfiguration search (see the comment above the first test).
class WasichaiApplicationTest {
    @Test
    fun `the annotation is a boot app that scans the app's own properties`() {
        val meta = MergedAnnotations.from(WasichaiApplication::class.java)
        assertThat(meta.isPresent(SpringBootApplication::class.java)).isTrue()
        assertThat(meta.isPresent(ConfigurationPropertiesScan::class.java)).isTrue()
    }

    @Test
    fun `exclude, excludeName and scanBasePackages alias through to SpringBootApplication`() {
        val boot = MergedAnnotations.from(Sample::class.java).get(SpringBootApplication::class.java)
        assertThat(boot.getClassArray("exclude")).containsExactly(ExcludedConfig::class.java)
        assertThat(boot.getStringArray("excludeName")).containsExactly("some.ExcludedAutoConfiguration")
        assertThat(boot.getStringArray("scanBasePackages")).containsExactly("com.example.app")
    }

    @WasichaiApplication(
        exclude = [ExcludedConfig::class],
        excludeName = ["some.ExcludedAutoConfiguration"],
        scanBasePackages = ["com.example.app"]
    )
    private class Sample

    private class ExcludedConfig
}
