package io.github.jakub.sqlmigrationvisualizer.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object MigrationFileNaming {

    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun buildFileName(
        pattern: String,
        version: Int,
        name: String?,
        extension: String,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        val safeExtension = extension.trim().removePrefix(".").ifBlank { "sql" }
        val normalizedPattern = pattern.trim().ifBlank { "{version}" }
        val safeName = slugify(name).ifBlank { "migration" }

        var fileName = normalizedPattern
            .replace("{version}", version.toString())
            .replace("{name}", safeName)
            .replace("{timestamp}", timestampFormatter.format(now))
            .replace("{extension}", safeExtension)

        fileName = fileName
            .replace(Regex("""[\\/]+"""), "_")
            .replace(Regex("""\s+"""), "_")
            .trim()
            .trim('_', '.')

        if (fileName.isBlank()) {
            fileName = "$version.$safeExtension"
        } else if (!fileName.contains('.')) {
            fileName += ".$safeExtension"
        } else if (!normalizedPattern.contains("{extension}")) {
            fileName += ".$safeExtension"
        }

        return fileName
    }

    fun slugify(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "_")
            .trim('_')
}
