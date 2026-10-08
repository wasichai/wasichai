package wasichai.core.data

import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiIdempotencyProperties
import wasichai.core.platform.WasichaiSchemas

object IdempotencyKeysFixtures {
    // a store no test reaches: these tests never send a key
    fun none(): IdempotencyKeys =
        IdempotencyKeys(
            mock(DatabaseClient::class.java),
            mock(WasichaiSchemas::class.java),
            JsonMapper.builder().build(),
            WasichaiIdempotencyProperties()
        ) { mock(TransactionalOperator::class.java) }
}
