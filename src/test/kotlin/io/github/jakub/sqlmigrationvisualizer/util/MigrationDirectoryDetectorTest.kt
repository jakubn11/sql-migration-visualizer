package io.github.jakub.sqlmigrationvisualizer.util

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationDirectoryDetectorTest {

    @Test
    fun `preferred directory wins even when relative`() {
        val projectDir = createTempDirectory("migration-dir-detector-").toFile()
        try {
            val detected = MigrationDirectoryDetector.detect(
                basePath = projectDir.absolutePath,
                preferredDirectory = "custom/migrations",
                existingMigrationPaths = emptyList()
            )

            assertEquals(File(projectDir, "custom/migrations").absolutePath, detected)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `existing migration directory wins over scanned schema files`() {
        val projectDir = createTempDirectory("migration-dir-detector-").toFile()
        try {
            val existingDir = File(projectDir, "src/main/sqldelight/app")
            existingDir.mkdirs()
            File(existingDir, "1.sqm").writeText("-- migration")

            val schemaDir = File(projectDir, "src/commonMain/sqldelight/other")
            schemaDir.mkdirs()
            File(schemaDir, "Tables.sq").writeText("CREATE TABLE users (id INTEGER PRIMARY KEY);")

            val detected = MigrationDirectoryDetector.detect(
                basePath = projectDir.absolutePath,
                preferredDirectory = "",
                existingMigrationPaths = listOf(File(existingDir, "1.sqm").absolutePath)
            )

            assertEquals(existingDir.absolutePath, detected)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `schema directory is discovered when no migrations exist yet`() {
        val projectDir = createTempDirectory("migration-dir-detector-").toFile()
        try {
            val schemaDir = File(projectDir, "src/main/resources/schema")
            schemaDir.mkdirs()
            File(schemaDir, "schema.sql").writeText("CREATE TABLE users (id INTEGER PRIMARY KEY);")

            val detected = MigrationDirectoryDetector.detect(
                basePath = projectDir.absolutePath,
                preferredDirectory = "",
                existingMigrationPaths = emptyList()
            )

            assertEquals(schemaDir.absolutePath, detected)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `configured directory hints are used before conventional fallback`() {
        val projectDir = createTempDirectory("migration-dir-detector-").toFile()
        try {
            val detected = MigrationDirectoryDetector.detect(
                basePath = projectDir.absolutePath,
                preferredDirectory = "",
                existingMigrationPaths = emptyList(),
                configuredDirectories = listOf("db/custom_migrations")
            )

            assertEquals(File(projectDir, "db/custom_migrations").absolutePath, detected)
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
