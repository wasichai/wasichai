plugins {
    id("wasichai.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    api(platform(lib("spring-boot-bom")))
    api(lib("spring-boot-autoconfigure"))
    // Spring needs this for Kotlin constructor binding / @ConfigurationProperties classes
    implementation(lib("kotlin-reflect"))

    testImplementation(lib("spring-boot-starter-test"))
    testImplementation(lib("reactor-test"))
    testImplementation(lib("kotlinx-coroutines-test"))
    // junit-platform-launcher comes from wasichai.kotlin-library
}
