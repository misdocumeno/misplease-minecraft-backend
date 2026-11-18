package io.github.misdocumeno.gradle

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * Shadow relocates `META-INF/services/<api>` file *paths* but writes the
 * provider class names inside unchanged. A relocated ServiceLoader API (Ktor,
 * Lettuce's JsonParser, ...) then fails at runtime with
 * `ServiceConfigurationError: Provider <relocated> not found`.
 *
 * Rewrites the provider lines after packaging: a line is prefixed with
 * [libPackage] only when the relocated class really exists in the jar, so
 * prefixes that were not relocated (or providers supplied by the server) are
 * left alone. Editing through the zip filesystem keeps every other entry
 * byte-identical. Call from `tasks.shadowJar { doLast { ... } }`.
 */
fun relocateServiceFileContents(jarFile: File, libPackage: String) {
    if (!jarFile.isFile) return
    val prefix = libPackage.trimEnd('.') + "."
    FileSystems.newFileSystem(jarFile.toPath(), emptyMap<String, Any>()).use { fileSystem ->
        val classes = HashSet<String>()
        Files.walk(fileSystem.getPath("/")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { classes.add(it.toString().removePrefix("/")) }
        }
        val services = fileSystem.getPath("/META-INF/services")
        if (!Files.isDirectory(services)) return
        Files.list(services).use { entries ->
            entries.filter { Files.isRegularFile(it) }.forEach { path ->
                val original = Files.readString(path)
                val rewritten = original.lineSequence().joinToString("\n") { line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.isEmpty() || trimmed.startsWith("#") -> line
                        classes.contains("$prefix$trimmed".replace('.', '/') + ".class") ->
                            line.replace(trimmed, "$prefix$trimmed")
                        else -> line
                    }
                }
                if (rewritten != original) Files.writeString(path, rewritten)
            }
        }
    }
}
