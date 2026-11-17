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

/**
 * Run the unit tests of modules whose name starts with -PpluginPrefix (same filter as
 * shadowJars). The image build runs this before baking the jars, so a red test stops the
 * deploy before smoke. Tests must stay pure: no Redis, no Bukkit server, no HTTP.
 */
tasks.register("tests") {
    group = "verification"
    description = "Run unit tests, optionally filtered by -PpluginPrefix"
    val prefix = providers.gradleProperty("pluginPrefix").orElse("")
    dependsOn(
        provider {
            val p = prefix.get()
            subprojects
                .filter { p.isEmpty() || it.name.startsWith(p) }
                .map { it.tasks.named("test") }
        },
    )
}
