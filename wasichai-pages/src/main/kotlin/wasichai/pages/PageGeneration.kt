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
    for (provider in providers) {
        val generated = provider.generated(definition) ?: continue
        if (generated.tab == null) {
            details += generated.component
        } else {
            moduleTabs += PageComponent(type = ComponentType.TAB, title = generated.tab, children = listOf(generated.component))
        }
    }

    val tabs = mutableListOf(PageComponent(type = ComponentType.TAB, title = GeneratedTab.DETAILS, children = details))
    tabs += moduleTabs
    if (related.isNotEmpty()) {
        tabs += PageComponent(type = ComponentType.TAB, title = GeneratedTab.RELATED, children = related)
    }
    tabs += PageComponent(type = ComponentType.TAB, title = GeneratedTab.HISTORY, children = listOf(PageComponent(type = ComponentType.HISTORY)))
    return tabs
}
