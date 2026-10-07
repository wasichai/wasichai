package wasichai.pages

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.data.WorkflowStates
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.metadata.ObjectDefinition
import wasichai.core.metadata.RelationshipService
import wasichai.forms.FormService
import java.util.UUID

data class PageComponentRequest(
    val type: String,
    val column: Int = 1,
    val title: String? = null,
    val layout: String? = null,
    val children: List<PageComponentRequest> = emptyList(),
    val region: String? = null,
    val relationship: String? = null,
    val fields: List<String>? = null,
    val form: String? = null,
    val field: String? = null,
    val visible: Boolean? = null,
    val editable: Boolean? = null,
    val geometry: String? = null,
    val content: String? = null,
    val action: String? = null,
    val transition: String? = null,
    val target: String? = null,
    val url: String? = null,
    val style: String? = null,
    // TAB only. trimmed and upper-cased; blank is none
    val key: String? = null
)

data class PageDefinitionRequest(
    val page: PageComponentRequest? = null
)

data class CreatePageRequest(
    val objectName: String,
    val name: String,
    val label: String,
    val kind: String? = null,
    // the client picks a template, it does not describe one -- a bare name, not the whole catalogue entry
    val template: String? = null,
    val definition: PageDefinitionRequest = PageDefinitionRequest()
)

// null means "leave as it is"
data class UpdatePageRequest(
    val label: String? = null,
    val template: String? = null,
    val definition: PageDefinitionRequest? = null
)

// a page plus the two things the wire needs that the row does not carry
data class ResolvedPage(
    val page: Page,
    val objectName: String?,
    val generated: Boolean
)

// kinds travel as lower-kebab in urls; enum names use underscores.
val PageKind.slug: String get() = name.lowercase().replace('_', '-')

fun parsePageKind(raw: String?): PageKind {
    if (raw.isNullOrBlank()) return PageKind.RECORD_DETAIL
    return PageKind.entries.firstOrNull { it.name.equals(raw.replace('-', '_'), ignoreCase = true) }
        ?: throw ValidationException(
            "Unknown page kind '$raw'",
            "kind",
            "must be one of ${PageKind.entries.joinToString(", ") { it.slug }}"
        )
}

@Service
class PageService(
    private val workflows: WorkflowStates,
    private val pages: PageRepository,
    private val metadata: MetadataService,
    private val relationships: RelationshipService,
    private val forms: FormService,
    private val currentUser: CurrentUser,
    private val componentTypes: PageComponentTypes
) {
    suspend fun list(): List<ResolvedPage> {
        val user = currentUser.requireWithPermission(Actions.READ)
        val names = metadata.listObjects().associate { it.id to it.name }
        return pages.findAll(user.organizationId).map { ResolvedPage(it, names[it.objectId], false) }
    }

    suspend fun byName(name: String): ResolvedPage {
        val user = currentUser.requireWithPermission(Actions.READ)
        val page =
            pages.findByName(user.organizationId, name)
                ?: throw NotFoundException("Page '$name' does not exist")
        val names = metadata.listObjects().associate { it.id to it.name }
        return ResolvedPage(page, names[page.objectId], false)
    }

    suspend fun forObject(objectName: String): List<ResolvedPage> {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        return pages.findByObject(definition.obj.id).map { ResolvedPage(it, definition.obj.name, false) }
    }

    // stored page if the admin authored one, otherwise one derived from the metadata
    suspend fun resolve(
        objectName: String,
        kind: PageKind
    ): ResolvedPage {
        val user = currentUser.require()
        val definition = metadata.loadDefinition(user.organizationId, objectName)
        currentUser.requirePermission(user, Actions.READ, definition.obj.id)
        val stored = pages.findByObjectAndKind(definition.obj.id, kind)
        if (stored != null) return ResolvedPage(stored, definition.obj.name, false)
        return ResolvedPage(generate(definition, kind), definition.obj.name, true)
    }

    @Transactional
    suspend fun create(request: CreatePageRequest): ResolvedPage {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val name = requireValidPageName(request.name.trim().lowercase())
        val kind = parsePageKind(request.kind)
        val template = PageTemplate.parse(request.template)
        val definition = metadata.loadDefinition(user.organizationId, request.objectName.trim().lowercase())

        if (pages.findByName(user.organizationId, name) != null) {
            throw ConflictException("Page '$name' already exists")
        }
        // the db has a unique constraint on (object_id, kind); fail cleanly before it fires
        if (pages.findByObjectAndKind(definition.obj.id, kind) != null) {
            throw ConflictException("Object '${definition.obj.name}' already has a ${kind.slug} page")
        }

        val stored =
            pages.insert(
                Page(
                    id = UUID.randomUUID(),
                    organizationId = user.organizationId,
                    objectId = definition.obj.id,
                    name = name,
                    label = request.label.trim().ifBlank { definition.obj.label },
                    kind = kind,
                    template = template,
                    definition = validated(request.definition, template, definition)
                )
            )
        return ResolvedPage(stored, definition.obj.name, false)
    }

    @Transactional
    suspend fun update(
        name: String,
        request: UpdatePageRequest
    ): ResolvedPage {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val existing =
            pages.findByName(user.organizationId, name)
                ?: throw NotFoundException("Page '$name' does not exist")
        val objectDefinition =
            existing.objectId?.let { metadata.loadDefinitionById(user.organizationId, it) }
                ?: throw ValidationException("Page '$name' has no object", "objectName", "page is not bound to an object")

        val template = request.template?.let { PageTemplate.parse(it) } ?: existing.template
        val definition =
            when {
                request.definition != null -> validated(request.definition, template, objectDefinition)
                // template unchanged: keep the stored definition untouched. re-validating it would
                // refuse a label-only PUT the moment a module that owns one of its component types
                // (e.g. gis's MAP) is uninstalled -- the stored tree is trusted, not re-checked.
                template == existing.template -> existing.definition
                // the template changed with no new definition: the kept tree must still fit the new
                // regions, so it goes through validation again
                else -> validated(existing.definition.toRequest(), template, objectDefinition)
            }

        val stored =
            pages.update(
                existing.copy(
                    label = request.label?.trim()?.ifBlank { existing.label } ?: existing.label,
                    template = template,
                    definition = definition
                )
            )
        return ResolvedPage(stored, objectDefinition.obj.name, false)
    }

    @Transactional
    suspend fun delete(name: String) {
        val user = currentUser.requireWithPermission(Actions.MANAGE_METADATA)
        val existing =
            pages.findByName(user.organizationId, name)
                ?: throw NotFoundException("Page '$name' does not exist")
        pages.delete(existing.id)
    }

    // no page stored: build one from metadata, via generatedTabs -- one tab per thing you go
    // looking for: details first (the form, plus whatever a module puts beside it), then each
    // module's own tab, related lists, history last.
    private suspend fun generate(
        definition: ObjectDefinition,
        kind: PageKind
    ): Page {
        // one tab per thing you go looking for. each holds one column, so the tab strip is the
        // layout and nothing sits beside anything.
        val related =
            relationships.forObject(definition.obj.name).map { side ->
                PageComponent(type = ComponentType.RELATED_LIST, title = side.label, relationship = side.relationship.name)
            }
        val tabs = generatedTabs(definition, componentTypes.providers, related)

        // one region, because a derived page has no admin intent to read: a template is an
        // authoring choice. and a region it leaves empty is one the admin cannot delete.
        val template = PageTemplate.ONE_REGION
        val contents = mapOf(PageRegion.MAIN to listOf(PageComponent(type = ComponentType.TABS, children = tabs)))
        // built from template.regions, not a literal, so this cannot drift from the validator's rule
        val root =
            PageComponent(
                type = ComponentType.PAGE,
                children =
                    template.regions.map {
                        PageComponent(type = ComponentType.REGION, region = it.name, children = contents[it.name] ?: emptyList())
                    }
            )

        return Page(
            // stable so the client can key on it, even though nothing is stored
            id = UUID.nameUUIDFromBytes("${definition.obj.id}:${kind.name}".toByteArray()),
            organizationId = definition.obj.organizationId,
            objectId = definition.obj.id,
            name = "${definition.obj.name}-${kind.slug}",
            label = definition.obj.label,
            kind = kind,
            template = template,
            definition = PageDefinition(root)
        )
    }

    private suspend fun validated(
        request: PageDefinitionRequest,
        template: PageTemplate,
        definition: ObjectDefinition
    ): PageDefinition {
        val root =
            request.page
                ?: throw ValidationException("A definition with no page", "page", "definition must hold one page node")

        // a free tree is free by design and bounded by defence. without this a hand-written
        // definition blows this validator's own stack before postgres ever sees it.
        inspect(listOf(root), depth = 1)

        val fieldNames = definition.fields.map { it.name }.toSet()
        val relationshipNames = relationships.forObject(definition.obj.name).map { it.relationship.name }.toSet()
        val all = flatten(listOf(root))
        // only pay for the lookup when a component actually points at a form
        val formNames = if (all.any { !it.form.isNullOrBlank() }) forms.storedNames(definition.obj.id) else emptySet()
        // same idea for actions: two lookups, only when a component actually fires one
        val hasAction = all.any { !it.action.isNullOrBlank() }
        val transitions = if (hasAction) workflows.transitionNames(definition.obj.organizationId, definition.obj.id) else emptySet()
        val objectNames = if (hasAction) metadata.listObjects().map { it.name }.toSet() else emptySet()
        // ?tab=KEY must name one tab: unique across the whole tree, nested strips included
        val tabKeys = mutableSetOf<String>()

        fun walk(
            components: List<PageComponentRequest>,
            parentLayout: PageLayout?,
            parent: ComponentType?
        ): List<PageComponent> =
            components.map { component ->
                val type = componentTypes.parse(component.type)
                val own = PageLayout.parse(component.layout)
                check(component, type, parentLayout, parent, definition, fieldNames, relationshipNames, formNames, transitions, objectNames, template)
                val key = TabKey.normalise(component.key)
                if (key != null && !tabKeys.add(key)) {
                    throw ValidationException("repeated tab key '$key'", "components", "a tab key names one tab in the page")
                }
                PageComponent(
                    type = type,
                    // the page and its regions are placed by the template, so a column means nothing
                    // there. normalise rather than refuse: toRequest() re-emits the stored node on
                    // every definition-less PUT, so refusing would break a plain label change forever.
                    column = if (type.container && parentLayout == null) 1 else component.column,
                    title = component.title?.trim()?.ifBlank { null },
                    layout = if (type == ComponentType.PAGE) PageLayout.SINGLE_COLUMN else own,
                    children = walk(component.children, if (type == ComponentType.PAGE) null else own, type),
                    region = if (type == ComponentType.REGION) PageRegion.parse(component.region) else null,
                    relationship = component.relationship?.trim()?.ifBlank { null },
                    fields = component.fields,
                    form = component.form?.trim()?.ifBlank { null },
                    field = component.field?.trim()?.ifBlank { null },
                    visible = component.visible,
                    editable = component.editable,
                    geometry = component.geometry?.trim()?.ifBlank { null },
                    content = component.content,
                    action = component.action?.let { ActionKind.parse(it) },
                    transition = component.transition?.trim()?.ifBlank { null },
                    target = component.target?.trim()?.ifBlank { null },
                    url = component.url?.trim()?.ifBlank { null },
                    style = component.action?.let { ActionStyle.parse(component.style) },
                    key = key
                )
            }

        return PageDefinition(walk(listOf(root), null, null).single())
    }

    // inspect() runs first and bounds the tree, so this never runs away.
    private fun flatten(components: List<PageComponentRequest>): List<PageComponentRequest> = components.flatMap { listOf(it) + flatten(it.children) }

    private fun inspect(
        components: List<PageComponentRequest>,
        depth: Int
    ): Int {
        if (components.isNotEmpty() && depth > MAX_DEPTH) {
            throw ValidationException("Components nested $depth deep", "components", "nesting must be at most $MAX_DEPTH deep")
        }
        var count = components.size
        components.forEach { count += inspect(it.children, depth + 1) }
        if (depth == 1 && count > MAX_COMPONENTS) {
            throw ValidationException("Page holds $count components", "components", "a page holds at most $MAX_COMPONENTS components")
        }
        return count
    }

    private fun check(
        component: PageComponentRequest,
        type: ComponentType,
        parentLayout: PageLayout?,
        parent: ComponentType?,
        definition: ObjectDefinition,
        fieldNames: Set<String>,
        relationshipNames: Set<String>,
        formNames: Set<String>,
        transitions: Set<String>,
        objectNames: Set<String>,
        template: PageTemplate
    ) {
        // the page is the root and its regions come from the template. neither is something an
        // admin places, so the rule is not "you may not add one" -- it is that the list must equal
        // the template's, exactly.
        if (type == ComponentType.PAGE && parent != null) {
            throw ValidationException("A PAGE inside the tree", "page", "PAGE is the root and nothing else")
        }
        if (parent == ComponentType.PAGE && type != ComponentType.REGION) {
            throw ValidationException("The page holds ${type.name}", "page", "PAGE accepts only REGION children")
        }
        if (type == ComponentType.REGION && parent != ComponentType.PAGE) {
            throw ValidationException("A REGION sits outside the page", "page", "REGION must be a child of PAGE")
        }
        if (parent == null && type != ComponentType.PAGE) {
            throw ValidationException("The root is a ${type.name}", "page", "the root must be a PAGE")
        }
        if (type == ComponentType.PAGE) {
            // refuse a non-REGION child before parsing its region: parse() speaks first otherwise,
            // and "Unknown region ''" sends the client hunting for a typo when what they actually
            // did was put a FORM or MAP straight under the page. a REGION with a blank name is
            // still a REGION -- that one falls through to parse() below and gets the true diagnosis.
            component.children.firstOrNull { componentTypes.parse(it.type) != ComponentType.REGION }?.let {
                throw ValidationException("The page holds ${componentTypes.parse(it.type).name}", "page", "PAGE accepts only REGION children")
            }
            val declared = template.regionNames
            val actual = component.children.map { PageRegion.parse(it.region) }
            val reason = "template '${template.value}' declares regions ${declared.joinToString(", ")}"
            actual.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
                throw ValidationException("The page holds region ${it.key} twice", "page", reason)
            }
            declared.firstOrNull { it !in actual }?.let { throw ValidationException("The page is missing region $it", "page", reason) }
            actual.firstOrNull { it !in declared }?.let { throw ValidationException("The page holds region $it", "page", reason) }
            if (actual != declared) throw ValidationException("The page lists its regions out of order", "page", reason)
        }

        // a tab strip whose children are not tabs means nothing, and a tab lives nowhere else.
        // this is a rule about what the types mean, not a limit on how the tree may be shaped.
        if (parent == ComponentType.TABS && type != ComponentType.TAB) {
            throw ValidationException("A tab strip holds ${type.name}", "components", "TABS accepts only TAB children")
        }
        if (type == ComponentType.TAB && parent != ComponentType.TABS) {
            throw ValidationException("A TAB sits outside a TABS", "components", "TAB must be a child of TABS")
        }
        // the same shape of rule for the other pair: a dynamic form is a list of this object's
        // fields and nothing else, and a field placement means nothing anywhere else.
        if (parent == ComponentType.DYNAMIC_FORM && type != ComponentType.FIELD) {
            throw ValidationException("A dynamic form holds ${type.name}", "components", "DYNAMIC_FORM accepts only FIELD children")
        }
        if (type == ComponentType.FIELD && parent != ComponentType.DYNAMIC_FORM) {
            throw ValidationException("A FIELD sits outside a DYNAMIC_FORM", "components", "FIELD must be a child of DYNAMIC_FORM")
        }
        if (!type.container && component.children.isNotEmpty()) {
            throw ValidationException("${type.name} carries children", "components", "only TABS, TAB and SECTION hold children")
        }
        // a key is what a link names, and a link opens a tab -- nothing else has one
        TabKey.normalise(component.key)?.let { key ->
            if (type != ComponentType.TAB) {
                throw ValidationException("key is only for TAB", "components", "${type.name} cannot carry a key")
            }
            if (!TabKey.valid(key)) {
                throw ValidationException("Invalid tab key '$key'", "components", "key must match ${TabKey.PATTERN.pattern}")
            }
        }
        if (parentLayout != null && (component.column < 1 || component.column > parentLayout.columns)) {
            throw ValidationException(
                "Component ${type.name} sits in column ${component.column}",
                "components",
                "column must be between 1 and ${parentLayout.columns} for layout ${parentLayout.value}"
            )
        }
        // a module's component: the tree rules above were pages', the rest is the module's
        componentTypes.provider(type)?.check(component, definition)
        val formName = component.form?.trim()?.ifBlank { null }
        when (type) {
            ComponentType.RELATED_LIST -> {
                val relationship =
                    component.relationship?.trim().orEmpty().ifBlank {
                        throw ValidationException("RELATED_LIST needs a relationship", "components", "relationship is required")
                    }
                if (relationship !in relationshipNames) {
                    throw ValidationException(
                        "Unknown relationship '$relationship'",
                        "components",
                        "must be a relationship of '${definition.obj.name}'"
                    )
                }
            }
            ComponentType.FORM -> {
                // a named form brings its own layout, so an inline field list would contradict it
                if (formName != null && component.fields != null) {
                    throw ValidationException(
                        "FORM names both a form and fields",
                        "components",
                        "form and fields are mutually exclusive"
                    )
                }
                component.fields?.firstOrNull { it !in fieldNames }?.let {
                    throw ValidationException(
                        "Unknown field '$it'",
                        "components",
                        "'${definition.obj.name}' has no field '$it'"
                    )
                }
                if (formName != null && formName !in formNames) {
                    throw ValidationException(
                        "Unknown form '$formName'",
                        "components",
                        "'${definition.obj.name}' has no form '$formName'"
                    )
                }
            }
            ComponentType.DYNAMIC_FORM -> {
                // the same field twice on one form is a slip of the drag, never an intent. across
                // two forms on the same page it is fine -- they are different forms.
                val placed = component.children.mapNotNull { it.field?.trim()?.ifBlank { null } }
                placed.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
                    throw ValidationException(
                        "Field '${it.key}' appears twice",
                        "components",
                        "a field belongs to one place on a form"
                    )
                }
            }
            ComponentType.FIELD -> {
                val name =
                    component.field?.trim()?.ifBlank { null }
                        ?: throw ValidationException("A FIELD names no field", "components", "a field placement must name a field")
                val meta =
                    definition.fields.firstOrNull { it.name == name }
                        ?: throw ValidationException(
                            "Unknown field '$name'",
                            "components",
                            "'${definition.obj.name}' has no field '$name'"
                        )
                // a placement may take away what the object grants, never add to it: no page turns a
                // read-only field into a writable one.
                if (component.editable == true && !meta.editable) {
                    throw ValidationException(
                        "Field '$name' is read-only",
                        "components",
                        "'${definition.obj.name}' does not let '$name' be edited"
                    )
                }
            }
            ComponentType.TEXT ->
                if (component.content.isNullOrBlank()) {
                    throw ValidationException("TEXT needs content", "components", "content must not be blank")
                }
            // reads what the record already has; nothing to configure, nothing to check
            ComponentType.HISTORY -> Unit

            // containers: no rules yet, later tasks add them
            ComponentType.TABS, ComponentType.TAB, ComponentType.SECTION, ComponentType.PAGE, ComponentType.REGION -> Unit

            ComponentType.ACTION -> {
                val kind =
                    component.action?.trim().orEmpty().ifBlank {
                        throw ValidationException("ACTION needs a kind", "components", "action must be TRANSITION or NAVIGATE")
                    }
                when (ActionKind.parse(kind)) {
                    ActionKind.TRANSITION -> {
                        val transition =
                            component.transition?.trim().orEmpty().ifBlank {
                                throw ValidationException("ACTION/TRANSITION needs a transition", "components", "transition is required")
                            }
                        if (transition !in transitions) {
                            throw ValidationException(
                                "Unknown transition '$transition'",
                                "components",
                                "'${definition.obj.name}' has no enabled workflow transition '$transition'"
                            )
                        }
                    }
                    ActionKind.NAVIGATE -> {
                        val target = component.target?.trim()?.ifBlank { null }
                        val url = component.url?.trim()?.ifBlank { null }
                        if ((target == null) == (url == null)) {
                            throw ValidationException(
                                "ACTION/NAVIGATE needs one destination",
                                "components",
                                "name exactly one of target or url"
                            )
                        }
                        // target names an object, never a relationship: the two namespaces
                        // can collide and a rule that silently prefers one is unreadable
                        if (target != null && target !in objectNames) {
                            throw ValidationException("Unknown object '$target'", "components", "target must be an object of this organization")
                        }
                        if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                            throw ValidationException("Unsupported url '$url'", "components", "url must start with http:// or https://")
                        }
                    }
                }
            }
        }
    }

    private fun requireValidPageName(name: String): String {
        if (!VALID_PAGE_NAME.matches(name)) {
            throw ValidationException(
                "Invalid page name '$name'",
                "name",
                "must match ${VALID_PAGE_NAME.pattern}"
            )
        }
        return name
    }

    companion object {
        // like an object name, but hyphens read better in a url
        private val VALID_PAGE_NAME = Regex("^[a-z][a-z0-9_-]{0,48}$")

        // 12, not 10: the scaffold spends two levels (PAGE, REGION), and the free tree inside a
        // region keeps exactly the ten ADR-021 gave it.
        private const val MAX_DEPTH = 12
        private const val MAX_COMPONENTS = 200
    }
}

// an update that omits the definition revalidates the stored one through here, so every field
// has to survive the trip. one that does not is lost on a plain label change.
private fun PageDefinition.toRequest(): PageDefinitionRequest = PageDefinitionRequest(page.toRequest())

private fun PageComponent.toRequest(): PageComponentRequest =
    PageComponentRequest(
        type = type.name,
        column = column,
        title = title,
        layout = layout.value,
        children = children.map { it.toRequest() },
        region = region?.name,
        relationship = relationship,
        fields = fields,
        form = form,
        field = field,
        visible = visible,
        editable = editable,
        geometry = geometry,
        content = content,
        action = action?.name,
        transition = transition,
        target = target,
        url = url,
        style = style?.name,
        key = key
    )
