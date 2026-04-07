package io.github.jakub.sqlmigrationvisualizer.validator

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema
import io.github.jakub.sqlmigrationvisualizer.model.ValidationIssueCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigrationValidatorTest {

    private val validator = MigrationValidator()

    @Test
    fun `version gaps expose missing versions and surrounding context`() {
        val result = validator.validate(
            migrations = listOf(
                migration(1),
                migration(4)
            ),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.VERSION_GAP }
        assertNotNull(issue)
        assertEquals(listOf(2, 3), issue.missingVersions)
        assertEquals(listOf(1, 4), issue.contextVersions)
        assertTrue(issue.suggestedFix.orEmpty().contains("continuous"))
    }

    @Test
    fun `duplicate versions expose conflicting file paths`() {
        val result = validator.validate(
            migrations = listOf(
                migration(2, "/tmp/a/2.sqm"),
                migration(2, "/tmp/b/2.sqm")
            ),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.DUPLICATE_VERSION }
        assertNotNull(issue)
        assertEquals(2, issue.relatedFilePaths.size)
        assertTrue(issue.relatedFilePaths.any { it.endsWith("/tmp/a/2.sqm") })
        assertTrue(issue.relatedFilePaths.any { it.endsWith("/tmp/b/2.sqm") })
    }

    @Test
    fun `alter table issue explains why missing target table matters`() {
        val result = validator.validate(
            migrations = listOf(
                migration(
                    version = 1,
                    filePath = "/tmp/1.sqm",
                    rawContent = "ALTER TABLE posts ADD COLUMN title TEXT;",
                    statements = listOf("ALTER TABLE posts ADD COLUMN title TEXT")
                )
            ),
            schemaVersions = listOf(
                SchemaVersion(
                    version = 0,
                    tables = mapOf(
                        "users" to TableSchema(
                            name = "users",
                            columns = listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true))
                        )
                    ),
                    migrationFile = null
                ),
                SchemaVersion(
                    version = 1,
                    tables = mapOf(
                        "users" to TableSchema(
                            name = "users",
                            columns = listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true))
                        )
                    ),
                    migrationFile = migration(
                        version = 1,
                        filePath = "/tmp/1.sqm",
                        rawContent = "ALTER TABLE posts ADD COLUMN title TEXT;",
                        statements = listOf("ALTER TABLE posts ADD COLUMN title TEXT")
                    )
                )
            )
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.ALTER_TABLE_TARGET_MISSING }
        assertNotNull(issue)
        assertEquals("posts", issue.tableName)
        assertEquals(listOf(0, 1), issue.contextVersions)
        assertTrue(issue.explanation.orEmpty().contains("precede"))
    }

    private fun migration(
        version: Int,
        filePath: String = "/tmp/$version.sqm",
        rawContent: String = "SELECT 1;",
        statements: List<String> = listOf("SELECT 1")
    ) = MigrationFile(
        version = version,
        filePath = filePath,
        fileName = filePath.substringAfterLast('/'),
        statements = statements,
        rawContent = rawContent
    )
}
