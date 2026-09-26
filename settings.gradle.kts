pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "wasichai"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

// a folder is a module when it has a build file. no list to keep in sync.
fun includeModules(dirs: List<File>) {
    dirs
        .filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
        .sortedBy { it.name }
        .forEach { dir ->
            include(dir.name)
            project(":${dir.name}").projectDir = dir
        }
}

fun children(parent: File): List<File> = parent.listFiles()?.toList() ?: emptyList()

// libraries live at the repo root as wasichai-*; build-logic is an included build, not a module.
// the sample apps are repositories of their own (ADR-033)
includeModules(children(rootDir).filter { it.name.startsWith("wasichai-") })
includeModules(children(file("starters")))
