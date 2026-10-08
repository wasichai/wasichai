package wasichai.files

import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.web.reactive.function.BodyInserters
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletionException

// the S3-compatible store against a real MinIO (testcontainers: CI, or any machine with docker). the same
// upload, download and cleanup the local store's FilesApiTest proves, through wasichai.files.store=s3.
@TestPropertySource(properties = ["wasichai.files.store=s3", "wasichai.files.cleanup.interval=0s"])
class S3FilesApiTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var store: FileStore

    @Autowired
    private lateinit var cleanup: StoredFileCleanup

    @Autowired
    private lateinit var db: DatabaseClient

    private val json = JsonMapper.builder().build()

    @Test
    fun `the s3 store puts, streams and deletes a key`(): Unit =
        runBlocking {
            assertThat(store).isInstanceOf(S3FileStore::class.java)
            val key = FileStore.keyOf(UUID.randomUUID(), UUID.randomUUID())
            store.put(key, FileFixtures.PDF, "application/pdf")
            assertThat(head(key)).isEqualTo("application/pdf")

            val joined = DataBufferUtils.join(store.open(key)).awaitSingle()
            val bytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
            DataBufferUtils.release(joined)
            assertThat(bytes).isEqualTo(FileFixtures.PDF)

            store.delete(key)
            assertThatThrownBy { runBlocking { head(key) } }.isInstanceOf(NoSuchKeyException::class.java)
            // deleting what is not there is no error
            store.delete(key)
        }

    @Test
    fun `an upload lands in the bucket, streams back, and the cleanup removes it once no record names it`() {
        val admin = bearer()
        val name = uniqueName("s3exp")
        client
            .post()
            .uri("/api/objects")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .bodyValue(
                mapOf(
                    "name" to name,
                    "label" to "Expediente",
                    "fields" to listOf(mapOf("name" to "codigo", "type" to "TEXT"), mapOf("name" to "foto", "type" to "IMAGE"))
                )
            ).exchange()
            .expectStatus()
            .isCreated
        val id =
            client
                .post()
                .uri("/api/objects/$name/records")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .bodyValue(mapOf("attributes" to mapOf("codigo" to "S-1")))
                .exchange()
                .expectStatus()
                .isCreated
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .let { json.readTree(it).get("id").asString() }

        val parts = MultipartBodyBuilder()
        parts.part("file", FileFixtures.PNG).filename("foto.png").contentType(MediaType.IMAGE_PNG)
        val uploaded =
            client
                .post()
                .uri("/api/objects/$name/records/$id/files/foto")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        val fileId =
            json
                .readTree(uploaded)
                .get("attributes")
                .get("foto")
                .get("id")
                .asString()
        val key = scalar("SELECT object_key FROM wasichai.stored_files WHERE id = '$fileId'")
        assertThat(runBlocking { head(key) }).isEqualTo("image/png")

        val download =
            client
                .get()
                .uri("/api/objects/$name/records/$id/files/foto")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
        assertThat(download.responseBody).isEqualTo(FileFixtures.PNG)
        assertThat(download.responseHeaders.contentDisposition.type).isEqualTo("inline")

        client
            .delete()
            .uri("/api/objects/$name/records/$id")
            .header(HttpHeaders.AUTHORIZATION, admin)
            .exchange()
            .expectStatus()
            .isNoContent
        runBlocking {
            db
                .sql("UPDATE wasichai.stored_files SET created_at = now() - interval '2 days' WHERE id = '$fileId'")
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
            assertThat(cleanup.runOnce()).isNotNull().isGreaterThanOrEqualTo(1)
        }
        assertThat(scalar("SELECT count(*)::text FROM wasichai.stored_files WHERE id = '$fileId'")).isEqualTo("0")
        assertThatThrownBy { runBlocking { head(key) } }.isInstanceOf(NoSuchKeyException::class.java)
    }

    // the stored content type, read from the bucket itself
    private suspend fun head(key: String): String =
        try {
            s3
                .headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(BUCKET)
                        .key(key)
                        .build()
                ).await()
                .contentType()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

    private fun scalar(sql: String): String =
        runBlocking {
            db
                .sql(sql)
                .map { row, _ -> row.get(0, String::class.java)!! }
                .one()
                .awaitFirstOrNull()!!
        }

    companion object {
        private const val BUCKET = "wasichai-it"

        private val minio: MinIOContainer by lazy {
            MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z").also { it.start() }
        }

        private val s3: S3AsyncClient by lazy {
            S3AsyncClient
                .builder()
                .endpointOverride(URI.create(minio.s3URL))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password)))
                .build()
                .also { it.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun s3Properties(registry: DynamicPropertyRegistry) {
            registry.add("wasichai.files.s3.endpoint") { s3.let { minio.s3URL } } // s3 first: the bucket exists before the app starts
            registry.add("wasichai.files.s3.bucket") { BUCKET }
            registry.add("wasichai.files.s3.access-key") { minio.userName }
            registry.add("wasichai.files.s3.secret-key") { minio.password }
            registry.add("wasichai.files.s3.path-style-access") { "true" }
        }
    }
}
