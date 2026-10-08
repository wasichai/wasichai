package wasichai.agent

// The tools the model is handed: their names, and the words that tell the model what they do.
// Nothing here is provider-specific any more. Embabel builds the JSON schema from the Kotlin
// signatures of AgentToolbox, so this file only has to hold what a signature cannot say.
//
// There are deliberately NO write tools (create_record, update_record, apply_transition...) in this
// phase. A write tool would let one sentence change tenant data with nothing in between, and the
// confirmation step that should sit in front of it does not exist in the UI yet. Add them the day
// the UI can show the caller exactly what is about to change and wait for a yes.
object AgentToolCatalog {
    const val LIST_OBJECTS = "list_objects"
    const val DESCRIBE_OBJECT = "describe_object"
    const val QUERY_RECORDS = "query_records"
    const val GET_RECORD = "get_record"
    const val COUNT_RECORDS = "count_records"
    const val LIST_RELATIONSHIPS = "list_relationships"
    const val RELATED_RECORDS = "related_records"
    const val RECORD_HISTORY = "record_history"
    const val AVAILABLE_TRANSITIONS = "available_transitions"

    val names: Set<String> =
        setOf(
            LIST_OBJECTS,
            DESCRIBE_OBJECT,
            QUERY_RECORDS,
            GET_RECORD,
            COUNT_RECORDS,
            LIST_RELATIONSHIPS,
            RELATED_RECORDS,
            RECORD_HISTORY,
            AVAILABLE_TRANSITIONS
        )

    // ---------------------------------------------------------------- tool descriptions
    // const, because they are read by @LlmTool, which needs compile-time constants.

    const val LIST_OBJECTS_DESCRIPTION =
        "List the custom objects (entity types) the current user is allowed to read in their organization. " +
            "Objects the user has no READ permission on are not listed. Takes no arguments. Start here."

    const val DESCRIBE_OBJECT_DESCRIPTION =
        "Describe one custom object: its label, geometry and the fields the current user may read. " +
            "Fields hidden from the user by field permissions are not returned. Call this before querying so " +
            "you use real field names."

    const val QUERY_RECORDS_DESCRIPTION =
        "Query the records of one object. Supports full-text search, equality filters on fields, sorting and a " +
            "bounding box. Returns at most $MAX_TOOL_LIMIT records regardless of the requested limit, plus the " +
            "true total so you can tell the user when you are only seeing part of the data. Geometry is not " +
            "returned here; use $GET_RECORD for one record's geometry."

    const val GET_RECORD_DESCRIPTION =
        "Read one record by id, with the fields the user may read, its workflow state and its geometry as GeoJSON."

    const val COUNT_RECORDS_DESCRIPTION =
        "Count the records of one object that match an optional search and filters, without fetching them. " +
            "Use this for 'how many' questions instead of querying and counting the page."

    const val LIST_RELATIONSHIPS_DESCRIPTION =
        "List the relationships that involve one object, with the related object on the other side. " +
            "Use the returned relationship name with $RELATED_RECORDS."

    const val RELATED_RECORDS_DESCRIPTION =
        "Follow a relationship from one record to the records on the other side. Returns at most $MAX_TOOL_LIMIT records."

    const val RECORD_HISTORY_DESCRIPTION =
        "The audit history of one record: who changed what and when. Changes to fields the user may not read " +
            "are not shown. Returns at most $MAX_TOOL_LIMIT entries, newest first."

    const val AVAILABLE_TRANSITIONS_DESCRIPTION =
        "The workflow transitions leaving one record's current state, each flagged as allowed or not with the " +
            "reason why. Read-only: this tool reports what could happen, it never moves the record."

    // ---------------------------------------------------------------- argument descriptions

    const val OBJECT_ARGUMENT = "Technical name of the object, as returned by $LIST_OBJECTS."

    const val ID_ARGUMENT = "Record id (uuid)."

    const val SEARCH_ARGUMENT = "Free text searched across the object's text fields."

    const val FILTERS_ARGUMENT = "Equality filters keyed by field name, e.g. {\"estado\": \"activo\"}."

    const val SORT_ARGUMENT = "Field name to sort by."

    const val DIRECTION_ARGUMENT = "Sort direction, 'asc' or 'desc'. Defaults to asc."

    const val LIMIT_ARGUMENT =
        "How many records to return, 1-$MAX_TOOL_LIMIT. Anything larger is clamped to $MAX_TOOL_LIMIT."

    const val HISTORY_LIMIT_ARGUMENT =
        "How many entries to return, 1-$MAX_TOOL_LIMIT. Anything larger is clamped to $MAX_TOOL_LIMIT."

    const val RELATIONSHIP_ARGUMENT = "Technical name of the relationship, from $LIST_RELATIONSHIPS."

    const val RELATED_DIRECTION_ARGUMENT =
        "Only for a relationship from an object to itself: 'forward' (the default) or 'inverse', as $LIST_RELATIONSHIPS " +
            "returns it. Leave it out for any other relationship."

    const val BBOX_ARGUMENT = "Spatial filter as minX,minY,maxX,maxY in EPSG:4326."
}
