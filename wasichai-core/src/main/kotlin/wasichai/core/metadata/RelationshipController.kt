package wasichai.core.metadata

import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.identity.CurrentUser
import java.util.UUID

data class RelationshipResponse(
    val id: String,
    val name: String,
    val label: String,
    val inverseLabel: String?,
    val type: String,
    val source: String,
    val target: String,
    val fieldName: String?,
    val joinTable: String?
)

// the same relationship as seen from one object: which object is on the other end, and how many.
// direction: forward or inverse, only on a self-relationship (listed once per direction); left out
// for any other, so its entry reads as before.
data class RelatedSideResponse(
    val relationship: String,
    val label: String,
    val type: String,
    val objectName: String,
    val objectLabel: String,
    val many: Boolean,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val direction: String? = null
)

@Component
class RelationshipMapper(
    private val objects: CustomObjectRepository,
    private val fields: CustomFieldRepository
) {
    suspend fun toResponse(
        relationship: Relationship,
        organizationId: UUID
    ): RelationshipResponse =
        RelationshipResponse(
            id = relationship.id.toString(),
            name = relationship.name,
            label = relationship.label,
            inverseLabel = relationship.inverseLabel,
            type = relationship.type.name,
            source = objects.findById(organizationId, relationship.sourceObjectId)?.name.orEmpty(),
            target = objects.findById(organizationId, relationship.targetObjectId)?.name.orEmpty(),
            fieldName = relationship.relationFieldId?.let { fields.findById(it)?.name },
            joinTable = relationship.joinTable
        )

    fun toResponse(side: RelatedSide): RelatedSideResponse =
        RelatedSideResponse(
            relationship = side.relationship.name,
            label = side.label,
            type = side.relationship.type.name,
            objectName = side.otherObject.name,
            objectLabel = side.otherObject.pluralLabel,
            many = side.many,
            direction = side.direction?.wire
        )
}

@RestController
@RequestMapping("/api/relationships")
class RelationshipController(
    private val relationships: RelationshipService,
    private val mapper: RelationshipMapper,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun list(): List<RelationshipResponse> {
        val organizationId = currentUser.require().organizationId
        return relationships.list().map { mapper.toResponse(it, organizationId) }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @Valid @RequestBody request: CreateRelationshipRequest
    ): RelationshipResponse = mapper.toResponse(relationships.create(request), currentUser.require().organizationId)

    @PutMapping("/{name}")
    suspend fun update(
        @PathVariable name: String,
        @Valid @RequestBody request: UpdateRelationshipRequest
    ): RelationshipResponse = mapper.toResponse(relationships.update(name, request), currentUser.require().organizationId)

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable name: String
    ) = relationships.delete(name)
}
