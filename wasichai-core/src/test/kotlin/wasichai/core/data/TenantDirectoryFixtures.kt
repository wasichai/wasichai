package wasichai.core.data

import wasichai.core.platform.TenantDirectory
import wasichai.core.platform.TenantRef

object TenantDirectoryFixtures {
    // no tenant at all: forEachOrganization runs nothing
    fun none(): TenantDirectory = of(emptyList())

    // every tenant in [all]; the ones defining an object are [defining]'s answer
    fun of(
        all: List<TenantRef>,
        defining: (String) -> List<TenantRef> = { all }
    ): TenantDirectory =
        object : TenantDirectory {
            override suspend fun organizations(): List<TenantRef> = all

            override suspend fun organizationsWithObject(objectName: String): List<TenantRef> = defining(objectName)
        }
}
