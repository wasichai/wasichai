package wasichai.core.data

import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.metadata.CustomFieldRepository
import wasichai.core.metadata.CustomObjectRepository
import wasichai.core.platform.WasichaiSchemas

object RelationTargetsFixtures {
    // for definitions with no RELATION field: nothing is ever looked up
    fun none(): RelationTargets =
        RelationTargets(
            mock(DatabaseClient::class.java),
            mock(WasichaiSchemas::class.java),
            mock(CustomObjectRepository::class.java),
            mock(CustomFieldRepository::class.java),
            RecordReadScopesFixtures.none()
        )
}
