package wasichai.files

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.core.io.buffer.DataBuffer
import reactor.core.publisher.Flux
import software.amazon.awssdk.services.s3.S3AsyncClient
import wasichai.core.data.RecordWriteGuard
import wasichai.core.metadata.FieldTypeRegistry
import wasichai.core.platform.ModuleMigration
import wasichai.files.autoconfigure.WasichaiFilesAutoConfiguration
import wasichai.files.autoconfigure.WasichaiFilesS3AutoConfiguration
import wasichai.test.WasichaiContextRunner

class WasichaiFilesAutoConfigurationTest {
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiFilesS3AutoConfiguration::class.java, WasichaiFilesAutoConfiguration::class.java))

    @Test
    fun `files plugs FILE, IMAGE and a write guard into core, with its routes, store and cleanup`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(FieldTypeRegistry::class.java).types).endsWith(FILE, IMAGE)
            assertThat(context.getBeansOfType(RecordWriteGuard::class.java).values).hasAtLeastOneElementOfType(StoredFileGuard::class.java)
            assertThat(context.getBean(FileStore::class.java)).isInstanceOf(LocalFileStore::class.java)
            assertThat(context).hasSingleBean(FileController::class.java)
            assertThat(context).hasSingleBean(FileService::class.java)
            assertThat(context).hasSingleBean(StoredFileCleanup::class.java)
            assertThat(context).doesNotHaveBean(S3AsyncClient::class.java)
            val migration = context.getBeansOfType(ModuleMigration::class.java).values.single { it.name == "files" }
            assertThat(migration.location).isEqualTo("classpath:db/wasichai/files")
            assertThat(migration.order).isGreaterThan(ModuleMigration.MODULE_ORDER)
        }
    }

    @Test
    fun `switched off, nothing of it is there`() {
        runner.withPropertyValues("wasichai.files.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(FieldTypeRegistry::class.java).isInstalled(FILE)).isFalse()
            assertThat(context).doesNotHaveBean(FileController::class.java)
            assertThat(context).doesNotHaveBean(FileStore::class.java)
            assertThat(context.getBeansOfType(ModuleMigration::class.java).values.map { it.name }).doesNotContain("files")
        }
    }

    @Test
    fun `store=s3 builds the S3-compatible store from wasichai files s3`() {
        runner
            .withPropertyValues(
                "wasichai.files.store=s3",
                "wasichai.files.s3.bucket=evidencias",
                "wasichai.files.s3.endpoint=http://minio.invalid:9000",
                "wasichai.files.s3.access-key=k",
                "wasichai.files.s3.secret-key=s",
                "wasichai.files.s3.path-style-access=true"
            ).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(FileStore::class.java)).isInstanceOf(S3FileStore::class.java)
                assertThat(context).hasSingleBean(S3AsyncClient::class.java)
            }
    }

    @Test
    fun `store=s3 without a bucket fails at startup`() {
        runner.withPropertyValues("wasichai.files.store=s3").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("wasichai.files.s3.bucket")
        }
    }

    @Test
    fun `an app's own store wins over both`() {
        runner.withBean(FileStore::class.java, { OwnStore }).withPropertyValues("wasichai.files.store=s3").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(FileStore::class.java)).isSameAs(OwnStore)
        }
    }

    @Test
    fun `a size cap above what memory holds, or a cleanup delay under two seconds, is refused`() {
        runner.withPropertyValues("wasichai.files.max-bytes=3GB").run { context -> assertThat(context).hasFailed() }
        runner.withPropertyValues("wasichai.files.cleanup.delay=1s").run { context -> assertThat(context).hasFailed() }
    }

    private object OwnStore : FileStore {
        override suspend fun put(
            key: String,
            content: ByteArray,
            contentType: String
        ) = Unit

        override fun open(key: String): Flux<DataBuffer> = Flux.empty()

        override suspend fun delete(key: String) = Unit
    }
}
