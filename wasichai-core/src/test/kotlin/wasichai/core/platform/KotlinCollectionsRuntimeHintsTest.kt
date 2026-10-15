package wasichai.core.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class KotlinCollectionsRuntimeHintsTest {
    @Test
    fun `kotlin's own empty and built collections are reflectable in a native image`() {
        val hints = RuntimeHints()
        KotlinCollectionsRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(emptyList<String>(), emptySet<String>(), emptyMap<String, String>(), buildList { add(1) }).forEach {
            assertThat(RuntimeHintsPredicates.reflection().onType(it.javaClass)).accepts(hints)
        }
    }

    @Test
    fun `every name it registers is a class of the stdlib`() {
        KotlinCollectionsRuntimeHints.TYPES.forEach { Class.forName(it) }
    }
}
