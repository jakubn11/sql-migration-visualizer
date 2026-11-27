package io.github.jakub.sqlmigrationvisualizer.util

import io.github.jakub.sqlmigrationvisualizer.model.MigrationVersion
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal object MigrationFileNaming {

    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun buildFileName(
        pattern: String,
        version: MigrationVersion,
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

    /**
     * Rebuild a migration file name with a new version, preserving the original
     * naming style. Recognises the three supported patterns:
     *
     *   123.sql          → newVersion.sql
     *   123_foo.sql      → newVersion_foo.sql
     *   V123__foo.sql    → Vnewversion__foo.sql  (also V1.2__ and V1_2__)
     *
     * Returns null if the original name doesn't match any recognised pattern,
     * so the caller can decide how to recover.
     */
    fun renumberFileName(originalName: String, newVersion: MigrationVersion): String? {
        val flyway = Regex("""^([Vv])(\d+(?:[._]\d+)*)(__.+)$""").matchEntire(originalName)
        if (flyway != null) {
            return "${flyway.groupValues[1]}$newVersion${flyway.groupValues[3]}"
        }
        val numericPrefix = Regex("""^(\d+)([._\-].*)?$""").matchEntire(originalName)
        if (numericPrefix != null) {
            val suffix = numericPrefix.groupValues[2]
            return if (suffix.isEmpty()) "$newVersion" else "$newVersion$suffix"
        }
        return null
    }

    /**
     * The next whole version after every used one: the highest version's first
     * part plus one (`12` → `13`, `1.2` → `2`). Returns 1 for an empty collection.
     */
    fun nextFreeVersion(usedVersions: Collection<MigrationVersion>): MigrationVersion =
        MigrationVersion.nextAfter(usedVersions)
}
