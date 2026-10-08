package wasichai.files.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

@ConfigurationProperties("wasichai.files")
data class WasichaiFilesProperties(
    // false: no FILE or IMAGE type, no file routes, no cleanup, no migration
    val enabled: Boolean = true,
    // a field's cap when it names none, and the most any field may name. held in memory while checked.
    val maxBytes: DataSize = DataSize.ofMegabytes(10),
    // local (a directory) or s3 (an S3-compatible bucket). an app's own FileStore bean replaces both.
    val store: Store = Store.LOCAL,
    val local: Local = Local(),
    val s3: S3 = S3(),
    val cleanup: Cleanup = Cleanup()
) {
    enum class Store { LOCAL, S3 }

    data class Local(
        // relative to the working directory; every replica must see the same directory
        val path: String = "wasichai-files"
    )

    data class S3(
        val bucket: String? = null,
        // null: the AWS endpoint of the region. set it for MinIO, Ceph or another S3-compatible service
        val endpoint: String? = null,
        val region: String = "us-east-1",
        // both null: the sdk's default credentials chain (env, profile, instance role)
        val accessKey: String? = null,
        val secretKey: String? = null,
        // MinIO and most self-hosted services want path-style urls
        val pathStyleAccess: Boolean = false,
        // keys go under this prefix in the bucket
        val prefix: String = ""
    )

    data class Cleanup(
        // how often unreferenced files are looked for. zero: never
        val interval: Duration = Duration.ofHours(1),
        // how long a file no record names is kept: an upload not attached yet is one of them
        val delay: Duration = Duration.ofHours(24)
    )
}
