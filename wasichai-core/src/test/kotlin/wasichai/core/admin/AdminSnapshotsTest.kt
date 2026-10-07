package wasichai.core.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditDiff
import wasichai.core.audit.FieldChange
import java.time.Instant
import java.util.UUID

// issue 49 (ADR-049): what the admin trail stores, and the diff it reads back
class AdminSnapshotsTest {
    private val json = JsonMapper.builder().build()

    private val user =
        AdminUserResponse(UUID.randomUUID().toString(), "ana@example.com", "Ana", true, listOf("EDITOR"), listOf("GERENCIA"), Instant.now())

    private fun role(
        permissions: List<PermissionResponse> = emptyList(),
        fieldPermissions: List<FieldPermissionResponse> = emptyList()
    ) = RoleResponse(UUID.randomUUID().toString(), "EDITOR", "Editor", false, permissions, fieldPermissions)

    // as the audit api sees it: written as jsonb, read back as maps
    private fun stored(state: Map<String, Any?>): Map<String, Any?> =
        json.readValue(json.writeValueAsString(state), object : TypeReference<Map<String, Any?>>() {})

    private fun diff(
        before: Map<String, Any?>,
        after: Map<String, Any?>
    ): List<FieldChange> = AuditDiff.changes(stored(before), stored(after))

    @Test
    fun `a user is stored without a password or a timestamp`() {
        val state = AdminSnapshots.user(user)

        assertThat(state).containsOnlyKeys("email", "displayName", "enabled", "roles", "orgUnits")
    }

    @Test
    fun `a password change is only that it changed`() {
        val changed = diff(AdminSnapshots.user(user), AdminSnapshots.userUpdated(user, passwordChanged = true))

        assertThat(changed).containsExactly(FieldChange("passwordChanged", null, true))
        assertThat(AdminSnapshots.userUpdated(user, passwordChanged = false)).doesNotContainKey("passwordChanged")
    }

    @Test
    fun `a service account is stored without its secret, even when the answer carries it`() {
        val account =
            ServiceAccountResponse(UUID.randomUUID().toString(), "x", "rentas", true, listOf("SYNC"), Instant.now(), Instant.now(), clientSecret = "s3cr3t")

        val state = AdminSnapshots.serviceAccount(account)

        assertThat(state).containsOnlyKeys("name", "enabled", "roles")
        assertThat(json.writeValueAsString(state)).doesNotContain("s3cr3t")
    }

    @Test
    fun `replacing a permission set records both sets and the diff names only the grants that changed`() {
        val before =
            role(
                listOf(
                    PermissionResponse(null, "READ", true),
                    PermissionResponse("predio", "UPDATE", true),
                    PermissionResponse("predio", "DELETE", true),
                    PermissionResponse("recibo", "ANULAR", false)
                )
            )
        val after =
            role(
                listOf(
                    PermissionResponse(null, "READ", true),
                    PermissionResponse("predio", "UPDATE", true),
                    PermissionResponse("recibo", "ANULAR", true),
                    PermissionResponse(null, "CREATE", true)
                )
            )

        val beforeState = AdminSnapshots.permissions(before)
        val afterState = AdminSnapshots.permissions(after)

        // whole sets on both sides
        assertThat(beforeState).containsOnlyKeys("role", "*.READ", "predio.UPDATE", "predio.DELETE", "recibo.ANULAR")
        assertThat(afterState).containsOnlyKeys("role", "*.READ", "predio.UPDATE", "recibo.ANULAR", "*.CREATE")
        assertThat(diff(beforeState, afterState)).containsExactlyInAnyOrder(
            FieldChange("predio.DELETE", true, null),
            FieldChange("recibo.ANULAR", false, true),
            FieldChange("*.CREATE", null, true)
        )
    }

    @Test
    fun `the same set again changes nothing`() {
        val set = role(listOf(PermissionResponse(null, "READ", true), PermissionResponse("predio", "UPDATE", true)))

        assertThat(diff(AdminSnapshots.permissions(set), AdminSnapshots.permissions(set.copy(permissions = set.permissions.reversed())))).isEmpty()
    }

    @Test
    fun `field rules diff per field`() {
        val before =
            role(
                fieldPermissions =
                    listOf(
                        FieldPermissionResponse("predio", "area", read = true, write = false),
                        FieldPermissionResponse("predio", "valor", read = false, write = false)
                    )
            )
        val after = before.copy(fieldPermissions = listOf(FieldPermissionResponse("predio", "area", read = true, write = true)))

        assertThat(diff(AdminSnapshots.fieldPermissions(before), AdminSnapshots.fieldPermissions(after))).containsExactlyInAnyOrder(
            FieldChange("predio.area", mapOf("read" to true, "write" to false), mapOf("read" to true, "write" to true)),
            FieldChange("predio.valor", mapOf("read" to false, "write" to false), null)
        )
    }

    @Test
    fun `a role carries its grants, so its deletion says what it could do`() {
        val state = AdminSnapshots.role(role(listOf(PermissionResponse("predio", "READ", true))))

        assertThat(state).containsOnlyKeys("name", "label", "ownRecordsOnly", "permissions", "fieldPermissions")
        assertThat(state["permissions"]).isEqualTo(mapOf("predio.READ" to true))
    }
}
