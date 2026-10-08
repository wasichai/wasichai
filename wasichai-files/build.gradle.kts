plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai files: the FILE and IMAGE field types, a storage SPI with local and S3-compatible stores"

dependencies {
    api(project(":wasichai-core"))
    // the S3-compatible store, only when an app brings the sdk: compileOnly + @ConditionalOnClass
    compileOnly(libs.aws.sdk.s3)

    testImplementation(project(":wasichai-test"))
    testImplementation(libs.spring.boot.starter.webflux.test)
    testRuntimeOnly(libs.r2dbc.postgresql)
    testRuntimeOnly(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
    // S3FileStoreTest: the real sdk against a MinIO container (integration, CI)
    testImplementation(libs.aws.sdk.s3)
    testImplementation(libs.testcontainers.minio)
}
