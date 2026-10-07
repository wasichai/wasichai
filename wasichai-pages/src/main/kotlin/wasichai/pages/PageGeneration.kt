package wasichai.pages

import wasichai.core.metadata.ObjectDefinition

// the tab strip of a page generated from metadata. one tab per thing you go looking for, each
// holding one column: details (the form, plus what modules put there), the modules' own tabs,
// related lists, and history last.
suspend fun generatedTabs(
    definition: ObjectDefinition,
    providers: List<PageComponentProvider>,
    related: List<PageComponent>
): List<PageComponent> {
    val details = mutableListOf(PageComponent(type = ComponentType.FORM))
    val moduleTabs = mutableListOf<PageComponent>()
    // each tab is keyed by its title (D33). built-ins first: a module repeating a taken key keeps
    // its tab but no key, so the page stays valid and ?tab= never means two tabs.
    val keys = mutableSetOf(GeneratedTab.DETAILS, GeneratedTab.RELATED, GeneratedTab.HISTORY)
    for (provider in providers) {
        val generated = provider.generated(definition) ?: continue
        if (generated.tab == null) {
            details += generated.component
        } else {
            val key = generated.tab.takeIf { keys.add(it) }
            moduleTabs += PageComponent(type = ComponentType.TAB, title = generated.tab, key = key, children = listOf(generated.component))
        }
    }

    val tabs = mutableListOf(tab(GeneratedTab.DETAILS, details))
    tabs += moduleTabs
    if (related.isNotEmpty()) {
        tabs += tab(GeneratedTab.RELATED, related)
    }
    tabs += tab(GeneratedTab.HISTORY, listOf(PageComponent(type = ComponentType.HISTORY)))
    return tabs
}

private fun tab(
    title: String,
    children: List<PageComponent>
) = PageComponent(type = ComponentType.TAB, title = title, key = title, children = children)
