tasks.register("clean") {
    group = "build"
    description = "Clean all subprojects"
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

/**
 * Build shadow jars for modules whose name starts with -PpluginPrefix (e.g. backend- / velocity-).
 * Used by Docker so one daemon compiles only the plugins that image needs.
 */
tasks.register("shadowJars") {
    group = "build"
    description = "Assemble shadow jars, optionally filtered by -PpluginPrefix"
    val prefix = providers.gradleProperty("pluginPrefix").orElse("")
    dependsOn(
        provider {
            val p = prefix.get()
            subprojects
                .filter { p.isEmpty() || it.name.startsWith(p) }
                .map { it.tasks.named("shadowJar") }
        },
    )
}
