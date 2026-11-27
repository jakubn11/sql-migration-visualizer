package io.github.jakub.sqlmigrationvisualizer.validator

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import io.github.jakub.sqlmigrationvisualizer.model.MigrationVersion
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema
import io.github.jakub.sqlmigrationvisualizer.model.ValidationIssueCode
import io.github.jakub.sqlmigrationvisualizer.model.ValidationResult
import io.github.jakub.sqlmigrationvisualizer.parser.SqlParser
import io.github.jakub.sqlmigrationvisualizer.model.ForeignKey
import io.github.jakub.sqlmigrationvisualizer.model.ValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(versions(2, 3), issue.missingVersions)
        assertEquals(versions(1, 4), issue.contextVersions)
        assertTrue(issue.suggestedFix.orEmpty().contains("continuous"))
    }

    @Test
    fun `huge version gap is summarised without enumerating millions of versions`() {
        val result = validator.validate(
            migrations = listOf(
                migration(1),
                migration(20_260_101)
            ),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.VERSION_GAP }
        assertNotNull(issue)
        assertTrue(issue.missingVersions.size <= 100)
        assertEquals(MigrationVersion.of(2), issue.missingVersions.first())
        assertTrue(issue.message.contains("Large version gap"))
        assertEquals(versions(1, 20_260_101), issue.contextVersions)
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
                    version = MigrationVersion.of(0),
                    tables = mapOf(
                        "users" to TableSchema(
                            name = "users",
                            columns = listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true))
                        )
                    ),
                    migrationFile = null
                ),
                SchemaVersion(
                    version = MigrationVersion.of(1),
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
        assertEquals(versions(0, 1), issue.contextVersions)
        assertTrue(issue.explanation.orEmpty().contains("precede"))
    }

    @Test
    fun `no migrations returns info result that is still valid`() {
        val result = validator.validate(migrations = emptyList(), schemaVersions = emptyList())

        assertTrue(result.isValid)
        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.NO_MIGRATIONS }
        assertNotNull(issue)
        assertEquals(ValidationSeverity.INFO, issue.severity)
    }

    @Test
    fun `empty migration file produces warning`() {
        val result = validator.validate(
            migrations = listOf(migration(1, statements = emptyList())),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.EMPTY_MIGRATION }
        assertNotNull(issue)
        assertEquals(ValidationSeverity.WARNING, issue.severity)
        assertEquals(MigrationVersion.of(1), issue.version)
    }

    @Test
    fun `transaction statement in migration produces one warning per file`() {
        val result = validator.validate(
            migrations = listOf(migration(1, statements = listOf("BEGIN", "INSERT INTO users VALUES (1)", "COMMIT"))),
            schemaVersions = emptyList()
        )

        val issues = result.issues.filter { it.code == ValidationIssueCode.TRANSACTION_STATEMENT }
        assertEquals(1, issues.size)
    }

    @Test
    fun `drop table statement produces info issue`() {
        val result = validator.validate(
            migrations = listOf(migration(1, statements = listOf("DROP TABLE IF EXISTS users"))),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.DROP_TABLE_STATEMENT }
        assertNotNull(issue)
        assertEquals(ValidationSeverity.INFO, issue.severity)
        assertEquals("users", issue.tableName)
    }

    @Test
    fun `self-referencing foreign key does not produce missing table error`() {
        val employeesTable = TableSchema(
            name = "employees",
            columns = listOf(
                ColumnDef("id", "INTEGER", isPrimaryKey = true),
                ColumnDef("manager_id", "INTEGER", nullable = true)
            ),
            primaryKey = listOf("id"),
            foreignKeys = listOf(ForeignKey(listOf("manager_id"), "employees", listOf("id")))
        )
        val version = SchemaVersion(version = MigrationVersion.of(1), tables = mapOf("employees" to employeesTable), migrationFile = null)

        val result = validator.validate(
            migrations = listOf(migration(1)),
            schemaVersions = listOf(version)
        )

        assertFalse(result.issues.any { it.code == ValidationIssueCode.FOREIGN_KEY_TARGET_MISSING })
    }

    @Test
    fun `foreign key referencing missing table produces error`() {
        val ordersTable = TableSchema(
            name = "orders",
            columns = listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true), ColumnDef("user_id", "INTEGER")),
            primaryKey = listOf("id"),
            foreignKeys = listOf(ForeignKey(listOf("user_id"), "users", listOf("id")))
        )
        val version = SchemaVersion(version = MigrationVersion.of(1), tables = mapOf("orders" to ordersTable), migrationFile = null)

        val result = validator.validate(
            migrations = listOf(migration(1)),
            schemaVersions = listOf(version)
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.FOREIGN_KEY_TARGET_MISSING }
        assertNotNull(issue)
        assertEquals("orders", issue.tableName)
    }

    @Test
    fun `schema-qualified alter target is not reported as missing`() {
        val schemaVersions = listOf(
            schemaVersion(0, mapOf("public.users" to table("public.users"))),
            schemaVersion(1, mapOf("public.users" to table("public.users")), migration(
                version = 1,
                statements = listOf("ALTER TABLE ONLY public.users ADD COLUMN email TEXT")
            ))
        )

        val result = validator.validate(listOf(schemaVersions[1].migrationFile!!), schemaVersions)

        assertTrue(result.issues.none { it.code == ValidationIssueCode.ALTER_TABLE_TARGET_MISSING })
    }

    @Test
    fun `alter target matching only by case is not reported as missing`() {
        val schemaVersions = listOf(
            schemaVersion(0, mapOf("users" to table("users"))),
            schemaVersion(1, mapOf("users" to table("users")), migration(
                version = 1,
                statements = listOf("ALTER TABLE Users ADD COLUMN email TEXT")
            ))
        )

        val result = validator.validate(listOf(schemaVersions[1].migrationFile!!), schemaVersions)

        assertTrue(result.issues.none { it.code == ValidationIssueCode.ALTER_TABLE_TARGET_MISSING })
    }

    @Test
    fun `the generated SQLite rebuild is not flagged as a manual transaction`() {
        val result = validator.validate(
            migrations = listOf(
                migration(
                    version = 1,
                    statements = listOf(
                        "PRAGMA foreign_keys=OFF",
                        "BEGIN TRANSACTION",
                        "CREATE TABLE users_new (id INTEGER PRIMARY KEY)",
                        "COMMIT",
                        "PRAGMA foreign_keys=ON"
                    )
                )
            ),
            schemaVersions = emptyList()
        )

        assertTrue(result.issues.none { it.code == ValidationIssueCode.TRANSACTION_STATEMENT })
    }

    @Test
    fun `altering a table created earlier in the same migration is not reported`() {
        val result = validateWithTimeline(
            "CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT)",
            "ALTER TABLE ONLY tags ADD CONSTRAINT tags_name_key UNIQUE (name)",
            "CREATE TABLE drafts (id INTEGER)",
            "ALTER TABLE drafts RENAME TO posts",
            "ALTER TABLE posts ADD COLUMN title TEXT"
        )

        assertTrue(result.issues.none { it.code == ValidationIssueCode.ALTER_TABLE_TARGET_MISSING })
    }

    @Test
    fun `ALTER TABLE IF EXISTS is not reported for a missing table`() {
        val result = validateWithTimeline(
            "ALTER TABLE IF EXISTS archive ADD COLUMN note TEXT",
            "ALTER TABLE missing ADD COLUMN note TEXT"
        )

        val issues = result.issues.filter { it.code == ValidationIssueCode.ALTER_TABLE_TARGET_MISSING }
        assertEquals(listOf("missing"), issues.map { it.tableName })
    }

    @Test
    fun `timestamp versions in a row are not reported as gaps`() {
        val result = validator.validate(
            migrations = listOf(migration("202401151030"), migration("202402011200")),
            schemaVersions = emptyList()
        )

        assertTrue(result.issues.none { it.code == ValidationIssueCode.VERSION_GAP })
    }

    @Test
    fun `dotted versions are not reported as gaps`() {
        val result = validator.validate(
            migrations = listOf(migration("1"), migration("1.1"), migration("1_2"), migration("2")),
            schemaVersions = emptyList()
        )

        assertTrue(result.issues.none { it.code == ValidationIssueCode.VERSION_GAP })
    }

    @Test
    fun `versions that differ only by trailing zeros are duplicates`() {
        val result = validator.validate(
            migrations = listOf(migration("1"), migration("1.0")),
            schemaVersions = emptyList()
        )

        val issue = result.issues.firstOrNull { it.code == ValidationIssueCode.DUPLICATE_VERSION }
        assertNotNull(issue)
        assertEquals(2, issue.relatedFilePaths.size)
    }

    private fun table(name: String) = TableSchema(
        name = name,
        columns = listOf(ColumnDef(name = "id", type = "INTEGER", isPrimaryKey = true))
    )

    private fun schemaVersion(
        version: Int,
        tables: Map<String, TableSchema>,
        migrationFile: MigrationFile? = null
    ) = SchemaVersion(
        version = MigrationVersion.of(version.toLong()),
        tables = tables,
        migrationFile = migrationFile
    )

    private fun validateWithTimeline(vararg statements: String): ValidationResult {
        val migration = migration(1, statements = statements.toList())
        val timeline = SqlParser().buildSchemaTimeline(listOf("CREATE TABLE users (id INTEGER PRIMARY KEY)"), listOf(migration))
        return validator.validate(listOf(migration), timeline)
    }

    private fun versions(vararg values: Long) = values.map { MigrationVersion.of(it) }

    private fun migration(version: String) = MigrationFile(
        version = MigrationVersion.parse(version)!!,
        filePath = "/tmp/V${version}__step.sql",
        fileName = "V${version}__step.sql",
        statements = listOf("SELECT 1"),
        rawContent = "SELECT 1;"
    )

    private fun migration(
        version: Int,
        filePath: String = "/tmp/$version.sqm",
        rawContent: String = "SELECT 1;",
        statements: List<String> = listOf("SELECT 1")
    ) = MigrationFile(
        version = MigrationVersion.of(version.toLong()),
        filePath = filePath,
        fileName = filePath.substringAfterLast('/'),
        statements = statements,
        rawContent = rawContent
    )
}
