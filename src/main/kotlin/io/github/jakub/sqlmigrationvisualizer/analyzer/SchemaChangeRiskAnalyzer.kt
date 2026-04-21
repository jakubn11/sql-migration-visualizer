package io.github.jakub.sqlmigrationvisualizer.analyzer

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.ForeignKey
import io.github.jakub.sqlmigrationvisualizer.model.MigrationRisk
import io.github.jakub.sqlmigrationvisualizer.model.MigrationRiskItem
import io.github.jakub.sqlmigrationvisualizer.model.MigrationRiskLevel
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema

internal object SchemaChangeRiskAnalyzer {

    fun analyze(from: SchemaVersion, to: SchemaVersion): MigrationRisk {
        val items = mutableListOf<MigrationRiskItem>()

        val fromTables = from.tables
        val toTables = to.tables
        val tableNames = (fromTables.keys + toTables.keys).toSortedSet()

        for (tableName in tableNames) {
            val before = fromTables[tableName]
            val after = toTables[tableName]

            when {
                before == null && after != null -> {
                    val requiredColumns = after.columns.filter { !it.nullable && !it.isPrimaryKey && it.defaultValue == null }
                    if (requiredColumns.isNotEmpty()) {
                        items += MigrationRiskItem(
                            level = MigrationRiskLevel.MEDIUM,
                            title = "New table requires complete inserts",
                            detail = "${after.name} adds required column(s): ${requiredColumns.joinToString(", ") { it.name }}.",
                            tableName = after.name
                        )
                    }
                }

                before != null && after == null -> {
                    items += MigrationRiskItem(
                        level = MigrationRiskLevel.HIGH,
                        title = "Table dropped",
                        detail = "$tableName is removed entirely, which can delete data for every environment applying this migration.",
                        tableName = tableName
                    )
                }

                before != null && after != null -> {
                    items += analyzeTableDelta(before, after)
                }
            }
        }

        val score = items.sumOf(::scoreFor)
        val level = when {
            items.any { it.level == MigrationRiskLevel.HIGH } || score >= 6 -> MigrationRiskLevel.HIGH
            items.any { it.level == MigrationRiskLevel.MEDIUM } || score >= 2 -> MigrationRiskLevel.MEDIUM
            else -> MigrationRiskLevel.LOW
        }

        val headline = when {
            items.isEmpty() -> "Low-risk additive change"
            level == MigrationRiskLevel.HIGH -> "High-risk migration review recommended"
            level == MigrationRiskLevel.MEDIUM -> "Moderate migration review recommended"
            else -> "Mostly additive schema change"
        }

        return MigrationRisk(
            level = level,
            score = score,
            headline = headline,
            items = items.take(8)
        )
    }

    private fun analyzeTableDelta(before: TableSchema, after: TableSchema): List<MigrationRiskItem> {
        val items = mutableListOf<MigrationRiskItem>()

        val beforeColumns = before.columns.associateBy { it.name }
        val afterColumns = after.columns.associateBy { it.name }

        for (column in before.columns) {
            if (!afterColumns.containsKey(column.name)) {
                items += MigrationRiskItem(
                    level = MigrationRiskLevel.HIGH,
                    title = "Column dropped",
                    detail = "${before.name}.${column.name} is removed, which can discard data and break older queries.",
                    tableName = before.name,
                    columnName = column.name
                )
            }
        }

        for (column in after.columns) {
            val previous = beforeColumns[column.name]
            if (previous == null) {
                if (!column.nullable && !column.isPrimaryKey && column.defaultValue == null) {
                    items += MigrationRiskItem(
                        level = MigrationRiskLevel.HIGH,
                        title = "Required column added without default",
                        detail = "${after.name}.${column.name} is required and has no default, so existing rows need a backfill plan.",
                        tableName = after.name,
                        columnName = column.name
                    )
                }
                continue
            }

            analyzeColumnChange(after.name, previous, column)?.let(items::add)
        }

        if (normalizePrimaryKey(before.primaryKey) != normalizePrimaryKey(after.primaryKey)) {
            items += MigrationRiskItem(
                level = MigrationRiskLevel.HIGH,
                title = "Primary key changed",
                detail = "${after.name} changes its primary key definition, which often requires table rebuilds and careful data validation.",
                tableName = after.name
            )
        }

        if (normalizeForeignKeys(before.foreignKeys) != normalizeForeignKeys(after.foreignKeys)) {
            items += MigrationRiskItem(
                level = MigrationRiskLevel.MEDIUM,
                title = "Foreign keys changed",
                detail = "${after.name} changes foreign key relationships, which can affect writes, deletes, and seed order.",
                tableName = after.name
            )
        }

        return items
    }

    private fun analyzeColumnChange(
        tableName: String,
        before: ColumnDef,
        after: ColumnDef
    ): MigrationRiskItem? {
        val changes = mutableListOf<String>()
        var level = MigrationRiskLevel.LOW

        if (!before.type.equals(after.type, ignoreCase = true)) {
            val narrowing = isPotentiallyNarrowingTypeChange(before.type, after.type)
            level = if (narrowing) MigrationRiskLevel.HIGH else MigrationRiskLevel.MEDIUM
            changes += if (narrowing) {
                "type narrows from ${before.type} to ${after.type}"
            } else {
                "type changes from ${before.type} to ${after.type}"
            }
        }

        if (before.nullable && !after.nullable) {
            level = maxLevel(level, MigrationRiskLevel.MEDIUM)
            changes += "nullability tightens to NOT NULL"
        }

        if ((before.defaultValue ?: "") != (after.defaultValue ?: "")) {
            level = maxLevel(level, MigrationRiskLevel.LOW)
            changes += "default value changes"
        }

        if (before.isPrimaryKey != after.isPrimaryKey) {
            level = maxLevel(level, MigrationRiskLevel.HIGH)
            changes += "primary key membership changes"
        }

        if (changes.isEmpty()) return null

        val title = when (level) {
            MigrationRiskLevel.HIGH -> "High-impact column change"
            MigrationRiskLevel.MEDIUM -> "Column contract changed"
            MigrationRiskLevel.LOW -> "Column definition updated"
        }

        return MigrationRiskItem(
            level = level,
            title = title,
            detail = "$tableName.${after.name} ${changes.joinToString(", ")}.",
            tableName = tableName,
            columnName = after.name
        )
    }

    private fun scoreFor(item: MigrationRiskItem): Int =
        when (item.level) {
            MigrationRiskLevel.HIGH -> 4
            MigrationRiskLevel.MEDIUM -> 2
            MigrationRiskLevel.LOW -> 1
        }

    private fun maxLevel(left: MigrationRiskLevel, right: MigrationRiskLevel): MigrationRiskLevel =
        if (left.ordinal >= right.ordinal) left else right

    private fun normalizePrimaryKey(primaryKey: List<String>): List<String> =
        primaryKey.map { it.lowercase() }.sorted()

    private fun normalizeForeignKeys(foreignKeys: List<ForeignKey>): List<String> =
        foreignKeys.map { fk ->
            buildString {
                append(fk.columns.map { it.lowercase() }.sorted().joinToString(","))
                append("->")
                append(fk.referencedTable.lowercase())
                append("(")
                append(fk.referencedColumns.map { it.lowercase() }.sorted().joinToString(","))
                append(")")
            }
        }.sorted()

    private fun isPotentiallyNarrowingTypeChange(beforeType: String, afterType: String): Boolean {
        val before = beforeType.trim().lowercase()
        val after = afterType.trim().lowercase()
        if (before == after) return false
        if ((before.startsWith("bigint") && after.startsWith("int")) ||
            (before.startsWith("text") && after.startsWith("varchar")) ||
            (before.startsWith("double") && after.startsWith("float"))
        ) {
            return true
        }

        val beforeLength = lengthHint(before)
        val afterLength = lengthHint(after)
        return beforeLength != null && afterLength != null && afterLength < beforeLength
    }

    private fun lengthHint(type: String): Int? =
        Regex("""\((\d+)\)""").find(type)?.groupValues?.getOrNull(1)?.toIntOrNull()
}
