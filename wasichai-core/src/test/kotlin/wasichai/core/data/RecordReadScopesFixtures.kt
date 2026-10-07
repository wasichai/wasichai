package wasichai.core.data

import org.mockito.Mockito.mock

object RecordReadScopesFixtures {
    // no app scope: nothing is ever asked, the store is never touched
    fun none(): RecordReadScopes = RecordReadScopes(emptyList(), mock(RecordStore::class.java))
}
