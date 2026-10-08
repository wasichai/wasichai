package wasichai.files.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import wasichai.files.FileStore
import wasichai.files.S3FileStore
import java.net.URI

// the S3-compatible store, only with wasichai.files.store=s3 and the sdk on the classpath (named as a
// string, so nothing loads an sdk class otherwise). an app that wants s3 adds software.amazon.awssdk:s3.
@AutoConfiguration
@ConditionalOnClass(name = ["software.amazon.awssdk.services.s3.S3AsyncClient"])
@ConditionalOnProperty(prefix = "wasichai.files", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "wasichai.files", name = ["store"], havingValue = "s3")
@EnableConfigurationProperties(WasichaiFilesProperties::class)
class WasichaiFilesS3AutoConfiguration {
    // closed with the context (S3AsyncClient is AutoCloseable)
    @Bean
    @ConditionalOnMissingBean
    fun wasichaiFilesS3Client(properties: WasichaiFilesProperties): S3AsyncClient {
        val s3 = properties.s3
        val builder =
            S3AsyncClient
                .builder()
                .region(Region.of(s3.region))
                .forcePathStyle(s3.pathStyleAccess)
        s3.endpoint?.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        if (s3.accessKey != null || s3.secretKey != null) {
            require(!s3.accessKey.isNullOrBlank() && !s3.secretKey.isNullOrBlank()) {
                "wasichai.files.s3.access-key and secret-key go together"
            }
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey, s3.secretKey)))
        }
        return builder.build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun s3FileStore(
        client: S3AsyncClient,
        properties: WasichaiFilesProperties
    ): FileStore {
        val bucket = properties.s3.bucket?.takeIf { it.isNotBlank() } ?: error("wasichai.files.store=s3 needs wasichai.files.s3.bucket")
        return S3FileStore(client, bucket, properties.s3.prefix)
    }
}
