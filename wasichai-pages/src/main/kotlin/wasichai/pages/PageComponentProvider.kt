package wasichai.pages

import wasichai.core.common.ValidationException
import wasichai.core.metadata.ObjectDefinition

// a component type a module brings to pages (gis: MAP, workflow: WORKFLOW). pages keeps the tree
// rules -- where it may sit, no children, which column; the provider owns what the component
// means for an object. ADR-0025.
interface PageComponentProvider {
    val type: ComponentType

    // refuses a placed component that cannot work on this object. runs after pages' tree rules.
    fun check(
        component: PageComponentRequest,
        definition: ObjectDefinition
    ) = Unit

    // what a page generated from metadata gets from this type. null: nothing for this object.
    suspend fun generated(definition: ObjectDefinition): GeneratedComponent? = null
}

// tab == null: it joins the details tab, after the form. otherwise it gets a tab of its own titled
// `tab` (a key the client translates), after details and before related and history. the title is
// the tab's key too (D33), so it must be one: caught in the module's own test, not on a live page.
data class GeneratedComponent(
    val component: PageComponent,
    val tab: String? = null
) {
    init {
        require(tab == null || TabKey.valid(tab)) { "generated tab must be a tab key (${TabKey.PATTERN.pattern}): '$tab'" }
    }
}

// every type an admin may name: pages' own first, then the modules' in bean order
class PageComponentTypes(
    val providers: List<PageComponentProvider>
) {
    val types: List<ComponentType> = ComponentType.BUILT_IN + providers.map { it.type }

    private val byType: Map<ComponentType, PageComponentProvider> = providers.associateBy { it.type }

    init {
        val twice = types.groupBy { it }.filterValues { it.size > 1 }.keys
        check(twice.isEmpty()) { "page component type declared twice: ${twice.joinToString(", ")}" }
    }

    fun parse(raw: String): ComponentType =
        types.firstOrNull { it.name == raw.uppercase() }
            ?: throw ValidationException(
                "Unknown component '$raw'",
                "components",
                "must be one of ${types.joinToString(", ") { it.name }}"
            )

    fun provider(type: ComponentType): PageComponentProvider? = byType[type]
}
