// Resolves `-sources` jars for every runtimeClasspath artifact (including
// transitives) and writes their on-disk paths to dependency-sources/.source-jars.txt.
// Run: gradle --init-script scripts/fetch-sources.init.gradle.kts resolveSourceJars
// Then unzip each entry (format: <jar-path>|<group>/<name>/<version>) into dependency-sources/.
allprojects {
    afterEvaluate {
        if (configurations.findByName("runtimeClasspath") != null) {
            tasks.register("resolveSourceJars") {
                val runtime = configurations.getByName("runtimeClasspath")
                doLast {
                    val outDir = rootProject.file("dependency-sources")
                    outDir.mkdirs()
                    val indexFile = file("${outDir.absolutePath}/.source-jars.txt")
                    val entries = mutableListOf<String>()
                    var resolved = 0
                    runtime.resolvedConfiguration.resolvedArtifacts.forEach { art ->
                        if (art.classifier == null) {
                            val id = art.moduleVersion.id
                            runCatching {
                                val cfg = configurations.detachedConfiguration(
                                    dependencies.create("${id.group}:${id.name}:${id.version}:sources")
                                ).also { it.isTransitive = false }
                                cfg.files.forEach { jar ->
                                    if (jar.name.endsWith("-sources.jar")) {
                                        entries.add("${jar.absolutePath}|${id.group}/${id.name}/${id.version}")
                                        resolved++
                                    }
                                }
                            }
                        }
                    }
                    indexFile.writeText(entries.joinToString("\n"))
                    println("Resolved $resolved source jars -> ${indexFile.absolutePath}")
                }
            }
        }
    }
}