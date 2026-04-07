package io.github.jakub.sqlmigrationvisualizer.generator

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema
import kotlin.test.Test
import kotlin.test.assertContains

class MigrationGeneratorTest {

    private val generator = MigrationGenerator()

    @Test
    fun `uses add column for simple additive change`() {
        val from = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "INTEGER", nullable = false, isPrimaryKey = true)
                ),
                primaryKey = listOf("id")
            )
        )
        val to = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "email", type = "TEXT", nullable = false, defaultValue = "'unknown'")
                ),
                primaryKey = listOf("id")
            )
        )

        val sql = generator.generateMigration(from, to)

        assertContains(sql, "ALTER TABLE users ADD COLUMN email TEXT NOT NULL DEFAULT 'unknown';")
    }

    @Test
    fun `rebuild plan adds todo for new required column without default`() {
        val from = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "name", type = "TEXT", nullable = false)
                ),
                primaryKey = listOf("id")
            )
        )
        val to = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "full_name", type = "TEXT", nullable = false),
                    ColumnDef(name = "email", type = "TEXT", nullable = false)
                ),
                primaryKey = listOf("id")
            )
        )

        val sql = generator.generateMigration(from, to)

        assertContains(sql, "CREATE TABLE users__new")
        assertContains(sql, "-- TODO: Provide a value for required column users.email before running this migration.")
        assertContains(sql, "DROP TABLE users;")
        assertContains(sql, "ALTER TABLE users__new RENAME TO users;")
    }

    @Test
    fun `postgres dialect uses alter column statements for simple definition changes`() {
        val from = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "BIGINT", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "email", type = "TEXT")
                ),
                primaryKey = listOf("id")
            )
        )
        val to = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "BIGINT", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "email", type = "VARCHAR(255)", nullable = false, defaultValue = "'unknown@example.com'")
                ),
                primaryKey = listOf("id")
            )
        )

        val sql = generator.generateMigration(from, to, SqlDialect.POSTGRESQL)

        assertContains(sql, "ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(255);")
        assertContains(sql, "ALTER TABLE users ALTER COLUMN email SET NOT NULL;")
        assertContains(sql, "ALTER TABLE users ALTER COLUMN email SET DEFAULT 'unknown@example.com';")
    }

    @Test
    fun `mysql dialect uses change column for rename and modify column for definition updates`() {
        val from = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "BIGINT", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "name", type = "TEXT"),
                    ColumnDef(name = "email", type = "TEXT")
                ),
                primaryKey = listOf("id")
            )
        )
        val to = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef(name = "id", type = "BIGINT", nullable = false, isPrimaryKey = true),
                    ColumnDef(name = "full_name", type = "TEXT"),
                    ColumnDef(name = "email", type = "VARCHAR(255)", nullable = false)
                ),
                primaryKey = listOf("id")
            )
        )

        val sql = generator.generateMigration(from, to, SqlDialect.MYSQL)

        assertContains(sql, "ALTER TABLE users CHANGE COLUMN name full_name TEXT;")
        assertContains(sql, "ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL;")
    }

    private fun schemaVersion(version: Int, table: TableSchema): SchemaVersion =
        SchemaVersion(
            version = version,
            tables = mapOf(table.name to table),
            migrationFile = null
        )
}
