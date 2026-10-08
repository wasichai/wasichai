package wasichai.files

import kotlinx.coroutines.future.await
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

// an S3-compatible bucket (AWS S3, MinIO, Ceph...), through the sdk's async client: no thread waits on
// the network. the key is the object's key in the bucket, under an optional prefix.
class S3FileStore(
    private val client: S3AsyncClient,
    private val bucket: String,
    prefix: String = ""
) : FileStore {
    private val prefix: String = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override suspend fun put(
        key: String,
        content: ByteArray,
        contentType: String
    ) {
        val request =
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(objectKey(key))
                .contentType(contentType)
                .contentLength(content.size.toLong())
                .build()
        client.putObject(request, AsyncRequestBody.fromBytes(content)).await()
    }

    override fun open(key: String): Flux<DataBuffer> {
        val request =
            GetObjectRequest
                .builder()
                .bucket(bucket)
                .key(objectKey(key))
                .build()
        return Mono
            .fromFuture { client.getObject(request, AsyncResponseTransformer.toPublisher()) }
            .flatMapMany { publisher -> Flux.from(publisher) }
            .map { buffer -> DefaultDataBufferFactory.sharedInstance.wrap(buffer) }
    }

    // s3 answers a delete of a missing key with success
    override suspend fun delete(key: String) {
        val request =
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(objectKey(key))
                .build()
        client.deleteObject(request).await()
    }

    private fun objectKey(key: String): String = prefix + FileStore.requireKey(key)
}
