package io.github.jakub.sqlmigrationvisualizer.analyzer

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.ForeignKey
import io.github.jakub.sqlmigrationvisualizer.model.MigrationRiskLevel
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `table dropped produces high risk`() {
        val before = schemaVersion(version = 1, table = TableSchema("logs", listOf(ColumnDef("id", "INTEGER"))))
        val after = SchemaVersion(version = 2, tables = emptyMap(), migrationFile = null)

        val risk = SchemaChangeRiskAnalyzer.analyze(before, after)

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
        assertTrue(risk.items.any { it.title == "Table dropped" })
    }

    @Test
    fun `new table with only nullable columns produces no risk items`() {
        val before = SchemaVersion(version = 1, tables = emptyMap(), migrationFile = null)
        val after = schemaVersion(
            version = 2,
            table = TableSchema(
                name = "tags",
                columns = listOf(
                    ColumnDef("id", "INTEGER", isPrimaryKey = true),
                    ColumnDef("label", "TEXT", nullable = true)
                )
            )
        )

        val risk = SchemaChangeRiskAnalyzer.analyze(before, after)

        assertEquals(MigrationRiskLevel.LOW, risk.level)
        assertTrue(risk.items.isEmpty())
    }

    @Test
    fun `narrowing type change BIGINT to INT produces high risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("val", "BIGINT")))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("val", "INT"))))
        )

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
        assertTrue(risk.items.any { "narrows" in it.detail })
    }

    @Test
    fun `narrowing type change TEXT to VARCHAR produces high risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("bio", "TEXT")))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("bio", "VARCHAR(255)"))))
        )

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
    }

    @Test
    fun `varchar length reduction produces high risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("code", "VARCHAR(255)")))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("code", "VARCHAR(50)"))))
        )

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
        assertTrue(risk.items.any { "narrows" in it.detail })
    }

    @Test
    fun `non-narrowing type change INT to BIGINT produces medium risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("count", "INT")))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("count", "BIGINT"))))
        )

        assertEquals(MigrationRiskLevel.MEDIUM, risk.level)
        assertTrue(risk.items.any { it.title == "Column contract changed" })
    }

    @Test
    fun `tightening nullable to not null produces medium risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("name", "TEXT", nullable = true)))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("name", "TEXT", nullable = false))))
        )

        assertEquals(MigrationRiskLevel.MEDIUM, risk.level)
        assertTrue(risk.items.any { "nullability" in it.detail })
    }

    @Test
    fun `primary key change produces high risk`() {
        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("t", listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true)), primaryKey = listOf("id"))),
            schemaVersion(2, TableSchema("t", listOf(ColumnDef("uuid", "TEXT", isPrimaryKey = true)), primaryKey = listOf("uuid")))
        )

        assertEquals(MigrationRiskLevel.HIGH, risk.level)
        assertTrue(risk.items.any { it.title == "Primary key changed" })
    }

    @Test
    fun `foreign key change produces medium risk`() {
        val col = ColumnDef("user_id", "INTEGER")
        val fkBefore = ForeignKey(listOf("user_id"), "users", listOf("id"))
        val fkAfter = ForeignKey(listOf("user_id"), "accounts", listOf("id"))

        val risk = SchemaChangeRiskAnalyzer.analyze(
            schemaVersion(1, TableSchema("orders", listOf(col), foreignKeys = listOf(fkBefore))),
            schemaVersion(2, TableSchema("orders", listOf(col), foreignKeys = listOf(fkAfter)))
        )

        assertTrue(risk.level >= MigrationRiskLevel.MEDIUM)
        assertTrue(risk.items.any { it.title == "Foreign keys changed" })
    }

    @Test
    fun `no schema changes produces low risk with empty items`() {
        val table = TableSchema("users", listOf(ColumnDef("id", "INTEGER", isPrimaryKey = true)), primaryKey = listOf("id"))

        val risk = SchemaChangeRiskAnalyzer.analyze(schemaVersion(1, table), schemaVersion(2, table))

        assertEquals(MigrationRiskLevel.LOW, risk.level)
        assertTrue(risk.items.isEmpty())
        assertEquals("Low-risk additive change", risk.headline)
    }

    private fun schemaVersion(version: Int, table: TableSchema): SchemaVersion =
        SchemaVersion(
            version = version,
            tables = mapOf(table.name to table),
            migrationFile = null
        )
}
