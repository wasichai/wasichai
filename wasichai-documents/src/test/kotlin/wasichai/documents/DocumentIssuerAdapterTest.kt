package wasichai.documents

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.util.UUID

class DocumentIssuerAdapterTest {
    private val asked = mutableListOf<String>()

    private val types =
        object : DocumentTypeRepository(mock(DatabaseClient::class.java), JsonMapper.builder().build(), WasichaiSchemas("wasichai", "app_data")) {
            override suspend fun findByName(
                objectId: UUID,
                name: String
            ): DocumentType? {
                asked += name
                return if (name == "permiso") mock(DocumentType::class.java) else null
            }
        }

    private val adapter = DocumentIssuerAdapter(types, mock(DocumentService::class.java))

    // automation stores the name as the admin typed it; types are stored trimmed and lowercase
    @Test
    fun `a type is looked up the way it is stored`() =
        runTest {
            assertThat(adapter.typeExists(UUID.randomUUID(), " Permiso ")).isTrue()
            assertThat(adapter.typeExists(UUID.randomUUID(), "licencia")).isFalse()
            assertThat(asked).containsExactly("permiso", "licencia")
        }
}
