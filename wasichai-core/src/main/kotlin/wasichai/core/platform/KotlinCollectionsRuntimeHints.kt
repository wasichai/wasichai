package wasichai.core.platform

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

// emptyList(), buildList() and kin answer kotlin's own classes, not java's. jackson's kotlin module asks
// kotlin-reflect about any such value it writes; in a native image that fails unless the class is
// registered (ADR-068). every answer that holds an empty list goes through here.
class KotlinCollectionsRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?
    ) {
        TYPES.forEach {
            hints.reflection().registerType(TypeReference.of(it), MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS)
        }
    }

    companion object {
        val TYPES =
            listOf(
                "kotlin.collections.EmptyList",
                "kotlin.collections.EmptySet",
                "kotlin.collections.EmptyMap",
                "kotlin.collections.builders.ListBuilder",
                "kotlin.collections.builders.SetBuilder",
                "kotlin.collections.builders.MapBuilder"
            )
    }
}
