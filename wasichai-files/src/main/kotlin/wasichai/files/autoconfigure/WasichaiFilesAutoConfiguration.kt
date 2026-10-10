package wasichai.files.autoconfigure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.data.RecordService
import wasichai.core.identity.CurrentUser
import wasichai.core.metadata.MetadataService
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.files.FILE
import wasichai.files.FileController
import wasichai.files.FileDescriptorReadPolicies
import wasichai.files.FileDescriptorReadPolicy
import wasichai.files.FileFieldType
import wasichai.files.FileService
import wasichai.files.FileStore
import wasichai.files.IMAGE
import wasichai.files.LocalFileStore
import wasichai.files.StoredFileCleanup
import wasichai.files.StoredFileGuard
import wasichai.files.StoredFileRepository
import java.nio.file.Path

// files as a plug-in: two field types, a write guard, its own routes and a cleanup job. core finds the
// types and the guard through ObjectProviders, so no ordering against core is needed. ADR-0061.
// the S3 store is WasichaiFilesS3AutoConfiguration, only there with the sdk on the classpath.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class, WasichaiFilesS3AutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.files", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WasichaiFilesProperties::class)
class WasichaiFilesAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would always make it back off.
    // after every MODULE_ORDER module: its callback appends FILE and IMAGE to the type list they rebuild
    @Bean
    fun wasichaiFilesMigration(): ModuleMigration = ModuleMigration("files", "classpath:db/wasichai/files", ModuleMigration.MODULE_ORDER + 1)

    @Bean
    @ConditionalOnMissingBean(name = ["fileFieldType"])
    fun fileFieldType(
        schemas: WasichaiSchemas,
        objectMapper: JsonMapper,
        properties: WasichaiFilesProperties
    ): FileFieldType = FileFieldType(FILE, schemas, objectMapper, ceiling(properties))

    @Bean
    @ConditionalOnMissingBean(name = ["imageFieldType"])
    fun imageFieldType(
        schemas: WasichaiSchemas,
        objectMapper: JsonMapper,
        properties: WasichaiFilesProperties
    ): FileFieldType = FileFieldType(IMAGE, schemas, objectMapper, ceiling(properties))

    @Bean
    @ConditionalOnMissingBean
    fun storedFileRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): StoredFileRepository = StoredFileRepository(db, schemas)

    // the s3 auto-configuration runs first: with wasichai.files.store=s3 its store is already here
    @Bean
    @ConditionalOnMissingBean
    fun localFileStore(properties: WasichaiFilesProperties): FileStore {
        check(properties.store == WasichaiFilesProperties.Store.LOCAL) {
            "wasichai.files.store=s3 needs software.amazon.awssdk:s3 on the classpath"
        }
        return LocalFileStore(Path.of(properties.local.path))
    }

    // half the cleanup delay: an upload is attached well before the cleanup could take it
    @Bean
    @ConditionalOnMissingBean
    fun storedFileGuard(
        files: StoredFileRepository,
        properties: WasichaiFilesProperties
    ): StoredFileGuard {
        require(properties.cleanup.delay.toSeconds() >= 2) { "wasichai.files.cleanup.delay must be at least 2s" }
        return StoredFileGuard(files, properties.cleanup.delay.dividedBy(2))
    }

    @Bean
    @ConditionalOnMissingBean
    fun fileService(
        records: RecordService,
        metadata: MetadataService,
        currentUser: CurrentUser,
        files: StoredFileRepository,
        store: FileStore,
        types: List<FileFieldType>,
        policies: FileDescriptorReadPolicies
    ): FileService = FileService(records, metadata, currentUser, files, store, types.associateBy { it.type.name }, policies)

    // the app's FileDescriptorReadPolicy beans, as a RecordReadMask core asks everywhere a record leaves it (ADR-065).
    // no @ConditionalOnMissingBean: an app adds a policy, it never swaps out another's
    @Bean
    fun fileDescriptorReadPolicies(policies: ObjectProvider<FileDescriptorReadPolicy>): FileDescriptorReadPolicies =
        FileDescriptorReadPolicies(policies.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun fileController(files: FileService): FileController = FileController(files)

    @Bean
    @ConditionalOnMissingBean
    fun storedFileCleanup(
        files: StoredFileRepository,
        store: FileStore,
        clusterLock: ClusterLock,
        properties: WasichaiFilesProperties
    ): StoredFileCleanup = StoredFileCleanup(files, store, clusterLock, properties.cleanup.interval, properties.cleanup.delay)

    private fun ceiling(properties: WasichaiFilesProperties): Long {
        val bytes = properties.maxBytes.toBytes()
        // the upload is held in memory while it is checked: one array
        require(bytes in 1..Int.MAX_VALUE.toLong() - 8) { "wasichai.files.max-bytes must be between 1 byte and 2 GB" }
        return bytes
    }
}
