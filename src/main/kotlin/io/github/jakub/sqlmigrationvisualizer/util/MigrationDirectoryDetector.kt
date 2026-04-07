package io.github.jakub.sqlmigrationvisualizer.util

import java.io.File

internal object MigrationDirectoryDetector {

    private val conventionalDirectories = listOf(
        "src/main/sqldelight",
        "src/commonMain/sqldelight",
        "src/androidMain/sqldelight",
        "src/jvmMain/sqldelight",
        "src/iosMain/sqldelight",
        "db/migrations",
        "database/migrations",
        "migrations",
        "src/main/resources/db/migration",
        "src/main/resources/db/migrations",
        "src/main/resources/migrations",
        "src/main/resources/sql",
        "src/main/resources/schema"
    )

    fun detect(
        basePath: String?,
        preferredDirectory: String,
        existingMigrationPaths: List<String>,
        configuredDirectories: List<String> = emptyList()
    ): String {
        resolvePreferredDirectory(basePath, preferredDirectory)?.let { return it }

        val existingMigrationDirectory = existingMigrationPaths
            .asSequence()
            .mapNotNull { path -> File(path).parentFile?.absolutePath }
            .distinct()
            .firstOrNull()
        if (existingMigrationDirectory != null) {
            return existingMigrationDirectory
        }

        configuredDirectories
            .asSequence()
            .mapNotNull { configured -> resolvePreferredDirectory(basePath, configured) }
            .firstOrNull()
            ?.let { return it }

        val projectRoot = basePath?.takeIf { it.isNotBlank() }?.let(::File) ?: return ""

        findSchemaFileDirectory(projectRoot)?.let { return it.absolutePath }

        conventionalDirectories
            .asSequence()
            .map { candidate -> File(projectRoot, candidate) }
            .firstOrNull(File::exists)
            ?.let { return it.absolutePath }

        return projectRoot.absolutePath
    }

    private fun resolvePreferredDirectory(basePath: String?, preferredDirectory: String): String? {
        val trimmed = preferredDirectory.trim()
        if (trimmed.isEmpty()) return null

        val preferredFile = File(trimmed)
        return if (preferredFile.isAbsolute) {
            preferredFile.absolutePath
        } else {
            val projectRoot = basePath?.takeIf { it.isNotBlank() }?.let(::File) ?: return preferredFile.absolutePath
            File(projectRoot, trimmed).absolutePath
        }
    }

    private fun findSchemaFileDirectory(projectRoot: File): File? =
        projectRoot.walkTopDown()
            .onEnter { dir -> !shouldSkip(dir, projectRoot) }
            .maxDepth(8)
            .firstOrNull { file -> file.isFile && io.github.jakub.sqlmigrationvisualizer.parser.MigrationScanner.isSchemaFileName(file.name) }
            ?.parentFile

    private fun shouldSkip(dir: File, projectRoot: File): Boolean {
        if (dir == projectRoot) return false
        return dir.name in setOf(".git", ".gradle", ".idea", "build", "out", "node_modules")
    }
}
