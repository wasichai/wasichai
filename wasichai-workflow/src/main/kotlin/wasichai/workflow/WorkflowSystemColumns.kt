package wasichai.workflow

import wasichai.core.metadata.ObjectSchemaManager
import wasichai.core.platform.SystemColumn
import wasichai.core.platform.SystemColumnContributor

// a workflow adds the state column to an object's table (ADR-013), so no user field may take its
// name. core keeps the column mechanics; the reservation is ours.
class WorkflowSystemColumns : SystemColumnContributor {
    override fun systemColumns(): List<SystemColumn> = listOf(SystemColumn(ObjectSchemaManager.STATE_COLUMN, "TEXT", SCOPE))

    companion object {
        // shown to the field editor: "exists only when a workflow is attached"
        const val SCOPE = "WORKFLOW"
    }
}
