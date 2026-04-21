package io.github.jakub.sqlmigrationvisualizer.analyzer

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.MigrationRiskLevel
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaChangeRiskAnalyzerTest {

    @Test
    fun `drop column and add required column without default produce high risk`() {
        val before = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef("email", "TEXT")
                ),
                primaryKey = listOf("id")
            )
        )
        val after = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef("full_name", "TEXT", nullable = false)
                ),
                primaryKey = listOf("id")
            )
        )

        val risk = SchemaChangeRiskAnalyzer.analyze(before, after)

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
        assertTrue(risk.items.any { it.title == "Column dropped" })
        assertTrue(risk.items.any { it.title == "Required column added without default" })
    }

    @Test
    fun `simple additive nullable column stays low risk`() {
        val before = schemaVersion(
            version = 1,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", "INTEGER", nullable = false, isPrimaryKey = true)
                ),
                primaryKey = listOf("id")
            )
        )
        val after = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "users",
                columns = listOf(
                    ColumnDef("id", "INTEGER", nullable = false, isPrimaryKey = true),
                    ColumnDef("nickname", "TEXT")
                ),
                primaryKey = listOf("id")
            )
        )

        val risk = SchemaChangeRiskAnalyzer.analyze(before, after)

        assertEquals(MigrationRiskLevel.LOW, risk.level)
        assertTrue(risk.items.isEmpty())
    }

    private fun schemaVersion(version: Int, table: TableSchema): SchemaVersion =
        SchemaVersion(
            version = version,
            tables = mapOf(table.name to table),
            migrationFile = null
        )
}
