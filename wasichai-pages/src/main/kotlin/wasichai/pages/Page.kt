package wasichai.pages

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import wasichai.core.common.ValidationException
import java.util.UUID

enum class PageKind {
    RECORD_DETAIL
}

enum class PageLayout(
    @get:JsonValue val value: String,
    val columns: Int
) {
    SINGLE_COLUMN("single-column", 1),
    TWO_COLUMN("two-column", 2);

    companion object {
        fun parse(raw: String?): PageLayout {
            if (raw.isNullOrBlank()) return SINGLE_COLUMN
            return entries.firstOrNull { it.value.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException(
                    "Unknown layout '$raw'",
                    "layout",
                    "must be one of ${entries.joinToString(", ") { it.value }}"
                )
        }
    }
}

// a component's type is a name. pages knows the built-ins below; a module adds one through a
// PageComponentProvider (gis: MAP, workflow: WORKFLOW). parsing has to know what is installed, so
// it lives in PageComponentTypes. json: the bare name, exactly as the enum was written.
class ComponentType(
    @get:JsonValue val name: String
) {
    // a lower-case type would parse fine but never equal its upper-case twin in a Set/Map key --
    // catch that at construction, not at some baffling lookup miss three calls away.
    init {
        require(name == name.uppercase()) { "component type name must be upper-case: '$name'" }
    }

    // DYNAMIC_FORM is one: its fields are children, so without this the generic leaf rule would
    // refuse the very thing it exists to hold. module types are always leaves.
    val container: Boolean
        get() = this == TABS || this == TAB || this == SECTION || this == PAGE || this == REGION || this == DYNAMIC_FORM

    // what an admin may place. the template owns the rest.
    val placeable: Boolean get() = this != PAGE && this != REGION

    override fun equals(other: Any?): Boolean = other is ComponentType && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        // the root, and the slots the template puts in it. scaffolding: the palette never offers them.
        val PAGE = ComponentType("PAGE")
        val REGION = ComponentType("REGION")

        // containers. they hold children and draw nothing of their own.
        val TABS = ComponentType("TABS")
        val TAB = ComponentType("TAB")
        val SECTION = ComponentType("SECTION")

        val FORM = ComponentType("FORM")

        // a form the admin builds field by field, and one field placed in it. same pair shape as
        // TABS/TAB: one holds only the other, and the other lives nowhere else.
        val DYNAMIC_FORM = ComponentType("DYNAMIC_FORM")
        val FIELD = ComponentType("FIELD")

        val RELATED_LIST = ComponentType("RELATED_LIST")
        val TEXT = ComponentType("TEXT")

        // the record's audit trail
        val HISTORY = ComponentType("HISTORY")

        // a button: fires a transition, or goes somewhere
        val ACTION = ComponentType("ACTION")

        // the original's order, minus the types modules bring
        val BUILT_IN: List<ComponentType> =
            listOf(PAGE, REGION, TABS, TAB, SECTION, FORM, DYNAMIC_FORM, FIELD, RELATED_LIST, TEXT, HISTORY, ACTION)

        // stored json is read without judging it: a page may name a module that is gone. saving
        // it again goes through PageComponentTypes.parse, which does judge.
        @JvmStatic
        @JsonCreator
        fun of(name: String): ComponentType = ComponentType(name.trim().uppercase())
    }
}

enum class ActionKind {
    // apply a named workflow transition to this record
    TRANSITION,

    // go to an object's records, or out to a url
    NAVIGATE;

    companion object {
        fun parse(raw: String): ActionKind =
            entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ValidationException("Unknown action '$raw'", "components", "action must be one of ${entries.joinToString(", ")}")
    }
}

enum class ActionStyle {
    PRIMARY,
    SECONDARY;

    companion object {
        fun parse(raw: String?): ActionStyle =
            if (raw.isNullOrBlank()) {
                SECONDARY
            } else {
                entries.firstOrNull { it.name == raw.uppercase() }
                    ?: throw ValidationException("Unknown style '$raw'", "components", "style must be one of ${entries.joinToString(", ")}")
            }
    }
}

// what the UI renders. everything a component needs travels with it, children included.
data class PageComponent(
    val type: ComponentType,
    // which column of the PARENT container holds it
    val column: Int = 1,
    val title: String? = null,
    // container: how it lays its own children out. a leaf ignores it.
    val layout: PageLayout = PageLayout.SINGLE_COLUMN,
    val children: List<PageComponent> = emptyList(),
    // REGION: which of the template's regions this is. a key, not a word -- a blank title would
    // quietly destroy the page's structure, so this is deliberately not `title`.
    val region: PageRegion? = null,
    // RELATED_LIST: which relationship to follow
    val relationship: String? = null,
    // FORM: a subset of the object's fields, in order. null means all of them.
    val fields: List<String>? = null,
    // FORM: a stored form of the object, rendered with its sections. excludes fields.
    val form: String? = null,
    // FIELD: which field of the object this placement puts on the form. a key, not a label.
    val field: String? = null,
    // FIELD: what this placement shows of it. null means "whatever the object says", so a field
    // that later turns read-only turns read-only on every page that never had an opinion.
    val visible: Boolean? = null,
    val editable: Boolean? = null,
    // MAP: one geometry field. null draws every one the object has.
    val geometry: String? = null,
    // TEXT: the note to show
    val content: String? = null,
    // ACTION
    val action: ActionKind? = null,
    val transition: String? = null,
    val target: String? = null,
    val url: String? = null,
    val style: ActionStyle? = null,
    // TAB only: a stable name a link can use (?tab=KEY). upper-case token, unique in the page.
    // null is left out, so a page without keys is written exactly as before (D33).
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val key: String? = null
)

// a tab's key: what a link names. one rule for the validator and for module tabs.
object TabKey {
    val PATTERN = Regex("^[A-Z][A-Z0-9_]{0,39}$")

    // blank counts as none
    fun normalise(raw: String?): String? = raw?.trim()?.uppercase()?.ifBlank { null }

    fun valid(key: String): Boolean = PATTERN.matches(key)
}

// the tabs a generated page comes with. keys, not words: the backend has no language, so the
// client translates these and prints anything else as it was typed. each is its tab's key too.
object GeneratedTab {
    const val DETAILS = "DETAILS"
    const val RELATED = "RELATED"
    const val HISTORY = "HISTORY"
}

// one root, always. a single field rather than a one-element list, so "exactly one page" is
// unrepresentable rather than a rule something has to enforce.
data class PageDefinition(
    val page: PageComponent
)

data class Page(
    val id: UUID,
    val organizationId: UUID,
    val objectId: UUID?,
    val name: String,
    val label: String,
    val kind: PageKind,
    val template: PageTemplate,
    val definition: PageDefinition
)
