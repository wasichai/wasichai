package wasichai.core.metadata

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
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
import wasichai.core.platform.SystemColumns

@RestController
@RequestMapping("/api/objects")
class ObjectController(
    private val metadata: MetadataService,
    private val mapper: MetadataMapper,
    private val currentUser: CurrentUser
) {
    @GetMapping
    suspend fun list(): List<ObjectResponse> = metadata.listDefinitions().map(mapper::toObjectResponse)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @Valid @RequestBody request: CreateObjectRequest
    ): ObjectDefinitionResponse = mapper.toResponse(metadata.createObject(request), currentUser.require().organizationId)

    @GetMapping("/{object}")
    suspend fun get(
        @PathVariable("object") name: String
    ): ObjectDefinitionResponse = mapper.toResponse(metadata.definitionOf(name), currentUser.require().organizationId)

    @PutMapping("/{object}")
    suspend fun update(
        @PathVariable("object") name: String,
        @Valid @RequestBody request: UpdateObjectRequest
    ): ObjectResponse = mapper.toObjectResponse(metadata.updateObject(name, request))

    @DeleteMapping("/{object}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable("object") name: String
    ) = metadata.deleteObject(name)
}

// same metadata, addressed the way the spec describes it
@RestController
@RequestMapping("/api/metadata/objects")
class ObjectMetadataController(
    private val metadata: MetadataService,
    private val mapper: MetadataMapper,
    private val currentUser: CurrentUser
) {
    @GetMapping("/{object}")
    suspend fun definition(
        @PathVariable("object") name: String
    ): ObjectDefinitionResponse = mapper.toResponse(metadata.definitionOf(name), currentUser.require().organizationId)

    @GetMapping("/{object}/fields")
    suspend fun fields(
        @PathVariable("object") name: String
    ): List<FieldResponse> = mapper.toFieldResponses(metadata.definitionOf(name).fields, currentUser.require().organizationId)

    @PostMapping("/{object}/fields")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun addField(
        @PathVariable("object") name: String,
        @Valid @RequestBody request: FieldRequest
    ): FieldResponse = mapper.toFieldResponses(listOf(metadata.addField(name, request)), currentUser.require().organizationId).first()

    @PutMapping("/{object}/fields/{field}")
    suspend fun updateField(
        @PathVariable("object") name: String,
        @PathVariable("field") fieldName: String,
        @RequestBody request: UpdateFieldRequest
    ): FieldResponse =
        mapper
            .toFieldResponses(
                listOf(metadata.updateField(name, fieldName, request)),
                currentUser.require().organizationId
            ).first()

    @DeleteMapping("/{object}/fields/{field}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteField(
        @PathVariable("object") name: String,
        @PathVariable("field") fieldName: String
    ) = metadata.deleteField(name, fieldName)
}

// static and tenant-free: core's names plus the ones installed modules own. saying whether *this*
// object has a given column would make metadata ask the module, so the scope names the condition.
@RestController
@RequestMapping("/api/metadata/system-fields")
class SystemFieldController(
    private val systemColumns: SystemColumns
) {
    @GetMapping
    fun systemFields(): List<SystemFieldResponse> = systemColumns.all.map { SystemFieldResponse(it.name, it.type, it.scope) }
}
