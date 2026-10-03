package wasichai.core.data

import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.metadata.RelationshipRepository
import wasichai.core.platform.WasichaiSchemas

object AppendOnlyReferencesFixtures {
    // nothing append-only points anywhere: a delete runs straight through, no lock, no transaction
    fun none(): AppendOnlyReferences =
        AppendOnlyReferences(
            mock(DatabaseClient::class.java),
            mock(WasichaiSchemas::class.java),
            mock(CustomObjectRepository::class.java),
            mock(CustomFieldRepository::class.java) { emptyList<Any>() },
            mock(RelationshipRepository::class.java) { emptyList<Any>() }
        ) { error("no transaction without an append-only reference") }
}
