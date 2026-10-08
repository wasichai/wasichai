// kotlin-dsl brings kotlin-gradle-plugin 2.4.10 onto this build's classpath: GHSA-r937-wjx7-w2jp
// (build cache deserialization), fixed in 2.4.20. pin it to the catalog kotlin.
buildscript {
    dependencies {
        constraints {
            classpath(libs.kotlin.gradle.plugin)
        }
    }
}

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.ktlint.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // testkit builds are slow. one jvm, no parallel forks fighting over the gradle user home.
    maxParallelForks = 1
}
