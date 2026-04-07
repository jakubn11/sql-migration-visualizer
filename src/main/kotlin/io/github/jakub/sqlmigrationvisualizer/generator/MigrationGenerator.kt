package io.github.jakub.sqlmigrationvisualizer.generator

import io.github.jakub.sqlmigrationvisualizer.model.ColumnDef
import io.github.jakub.sqlmigrationvisualizer.model.ForeignKey
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.TableSchema

/**
 * Generates SQL migration statements by comparing two schema versions.
 *
 * The generator prefers lightweight ALTER statements for common changes
 * and falls back to a table rebuild when the selected dialect cannot
 * express the change safely.
 */
class MigrationGenerator {

    fun generateMigration(
        from: SchemaVersion,
        to: SchemaVersion,
        dialect: SqlDialect = SqlDialect.GENERIC
    ): String {
        val statements = mutableListOf<String>()
        val remainingFrom = from.tables.toMutableMap()
        val remainingTo = to.tables.toMutableMap()
        val comparableTables = mutableListOf<Pair<TableSchema, TableSchema>>()

        detectTableRenames(remainingFrom, remainingTo).forEach { (oldName, newName) ->
            val fromTable = remainingFrom.remove(oldName) ?: return@forEach
            val toTable = remainingTo.remove(newName) ?: return@forEach
            statements.add(renderRenameTable(oldName, newName, dialect))
            comparableTables += fromTable.copy(name = newName) to toTable
        }

        (remainingFrom.keys intersect remainingTo.keys).sorted().forEach { tableName ->
            val fromTable = remainingFrom.remove(tableName) ?: return@forEach
            val toTable = remainingTo.remove(tableName) ?: return@forEach
            comparableTables += fromTable to toTable
        }

        comparableTables.forEach { (fromTable, toTable) ->
            statements += generateTableChanges(fromTable, toTable, dialect)
        }

        remainingTo.toSortedMap().forEach { (_, table) ->
            statements.add(generateCreateTable(table))
        }

        remainingFrom.toSortedMap().forEach { (name, _) ->
            statements.add("DROP TABLE IF EXISTS $name;")
        }

        return if (statements.isEmpty()) {
            "-- No changes detected between version ${from.version} and version ${to.version}"
        } else {
            statements.joinToString("\n\n")
        }
    }

    private fun generateTableChanges(
        fromTable: TableSchema,
        toTable: TableSchema,
        dialect: SqlDialect
    ): List<String> {
        val renamePairs = detectColumnRenames(fromTable, toTable)
        val renamedSources = renamePairs.keys
        val renamedTargets = renamePairs.values.toSet()

        val fromByName = fromTable.columns.associateBy { it.name }
        val toByName = toTable.columns.associateBy { it.name }

        val addedColumns = toTable.columns.filter { it.name !in fromByName && it.name !in renamedTargets }
        val removedColumns = fromTable.columns.filter { it.name !in toByName && it.name !in renamedSources }
        val commonColumns = fromTable.columns.map { it.name }.toSet().intersect(toTable.columns.map { it.name }.toSet())

        val requiresRebuild =
            removedColumns.isNotEmpty() ||
                normalizePrimaryKey(fromTable.primaryKey) != normalizePrimaryKey(toTable.primaryKey) ||
                normalizeForeignKeys(fromTable.foreignKeys) != normalizeForeignKeys(toTable.foreignKeys)

        if (!requiresRebuild) {
            val statements = mutableListOf<String>()

            renamePairs.forEach { (oldName, newName) ->
                val renamedColumn = toByName[newName] ?: return@forEach
                statements += renderRenameColumn(toTable.name, oldName, renamedColumn, dialect)
            }

            val alterStatements = generateColumnAlterStatements(toTable.name, commonColumns, fromByName, toByName, dialect)
            if (alterStatements != null) {
                statements += alterStatements
                addedColumns.forEach { column ->
                    statements.add("ALTER TABLE ${toTable.name} ADD COLUMN ${buildColumnSql(column)};")
                }
                return statements
            }
        }

        return generateRebuildPlan(fromTable, toTable, renamePairs, dialect)
    }

    private fun generateColumnAlterStatements(
        tableName: String,
        commonColumns: Set<String>,
        fromByName: Map<String, ColumnDef>,
        toByName: Map<String, ColumnDef>,
        dialect: SqlDialect
    ): List<String>? {
        val statements = mutableListOf<String>()

        commonColumns.sorted().forEach { name ->
            val oldCol = fromByName[name] ?: return@forEach
            val newCol = toByName[name] ?: return@forEach
            if (isSameColumnDefinition(oldCol, newCol)) return@forEach
            if (oldCol.isPrimaryKey != newCol.isPrimaryKey) return null

            when (dialect) {
                SqlDialect.GENERIC -> return null
                SqlDialect.POSTGRESQL -> {
                    if (!oldCol.type.equals(newCol.type, ignoreCase = true)) {
                        statements += "ALTER TABLE $tableName ALTER COLUMN ${newCol.name} TYPE ${newCol.type};"
                    }
                    if (oldCol.nullable != newCol.nullable) {
                        statements += "ALTER TABLE $tableName ALTER COLUMN ${newCol.name} " +
                            if (newCol.nullable) "DROP NOT NULL;" else "SET NOT NULL;"
                    }
                    if (oldCol.defaultValue != newCol.defaultValue) {
                        statements += if (newCol.defaultValue == null) {
                            "ALTER TABLE $tableName ALTER COLUMN ${newCol.name} DROP DEFAULT;"
                        } else {
                            "ALTER TABLE $tableName ALTER COLUMN ${newCol.name} SET DEFAULT ${newCol.defaultValue};"
                        }
                    }
                }
                SqlDialect.MYSQL -> {
                    statements += "ALTER TABLE $tableName MODIFY COLUMN ${buildColumnSql(newCol)};"
                }
            }
        }

        return statements
    }

    private fun generateRebuildPlan(
        fromTable: TableSchema,
        toTable: TableSchema,
        renamePairs: Map<String, String>,
        dialect: SqlDialect
    ): List<String> {
        val statements = mutableListOf<String>()
        val tempTableName = "${toTable.name}__new"
        val sourceColumns = fromTable.columns.associateBy { it.name }
        val sharedColumns = mutableListOf<Pair<String, String>>()
        val notes = mutableListOf<String>()

        toTable.columns.forEach { targetColumn ->
            val renamedSource = renamePairs.entries.firstOrNull { it.value == targetColumn.name }?.key
            val sourceName = when {
                renamedSource != null -> renamedSource
                sourceColumns.containsKey(targetColumn.name) -> targetColumn.name
                else -> null
            }

            when {
                sourceName != null -> {
                    sharedColumns += targetColumn.name to sourceName
                }
                targetColumn.defaultValue != null -> {
                    sharedColumns += targetColumn.name to targetColumn.defaultValue
                }
                targetColumn.nullable || targetColumn.isPrimaryKey -> {
                    // Let the dialect apply NULL / implicit primary-key behavior.
                }
                else -> {
                    notes += "-- TODO: Provide a value for required column ${toTable.name}.${targetColumn.name} before running this migration."
                }
            }
        }

        statements += "-- Rebuild ${toTable.name} to apply complex schema changes safely."
        statements += generateCreateTable(toTable.copy(name = tempTableName))

        if (sharedColumns.isNotEmpty()) {
            val insertColumns = sharedColumns.joinToString(", ") { it.first }
            val selectColumns = sharedColumns.joinToString(", ") { (targetName, sourceName) ->
                if (targetName == sourceName) sourceName else "$sourceName AS $targetName"
            }
            statements += "INSERT INTO $tempTableName ($insertColumns)\nSELECT $selectColumns\nFROM ${fromTable.name};"
        }

        statements += notes
        statements += "DROP TABLE ${fromTable.name};"
        statements += renderRenameTable(tempTableName, toTable.name, dialect)
        return statements
    }

    private fun renderRenameTable(oldName: String, newName: String, dialect: SqlDialect): String =
        when (dialect) {
            SqlDialect.MYSQL -> "RENAME TABLE $oldName TO $newName;"
            else -> "ALTER TABLE $oldName RENAME TO $newName;"
        }

    private fun renderRenameColumn(
        tableName: String,
        oldName: String,
        newColumn: ColumnDef,
        dialect: SqlDialect
    ): String =
        when (dialect) {
            SqlDialect.MYSQL -> "ALTER TABLE $tableName CHANGE COLUMN $oldName ${buildColumnSql(newColumn)};"
            else -> "ALTER TABLE $tableName RENAME COLUMN $oldName TO ${newColumn.name};"
        }

    private fun detectTableRenames(
        fromTables: Map<String, TableSchema>,
        toTables: Map<String, TableSchema>
    ): List<Pair<String, String>> {
        val removed = fromTables.keys - toTables.keys
        val added = toTables.keys - fromTables.keys
        val renames = mutableListOf<Pair<String, String>>()
        val usedTargets = mutableSetOf<String>()

        removed.sorted().forEach { oldName ->
            val fromTable = fromTables[oldName] ?: return@forEach
            val candidate = added
                .filterNot { it in usedTargets }
                .firstOrNull { newName ->
                    val toTable = toTables[newName] ?: return@firstOrNull false
                    tableSignature(fromTable) == tableSignature(toTable)
                }
            if (candidate != null) {
                renames += oldName to candidate
                usedTargets += candidate
            }
        }

        return renames
    }

    private fun detectColumnRenames(fromTable: TableSchema, toTable: TableSchema): Map<String, String> {
        val removed = fromTable.columns.filter { oldCol -> toTable.columns.none { it.name == oldCol.name } }.toMutableList()
        val added = toTable.columns.filter { newCol -> fromTable.columns.none { it.name == newCol.name } }.toMutableList()
        val renames = linkedMapOf<String, String>()

        val iterator = removed.iterator()
        while (iterator.hasNext()) {
            val oldColumn = iterator.next()
            val candidates = added.filter { isRenameCompatible(oldColumn, it) }
            if (candidates.size == 1) {
                val target = candidates.first()
                renames[oldColumn.name] = target.name
                added.remove(target)
                iterator.remove()
            }
        }

        return renames
    }

    private fun isRenameCompatible(fromColumn: ColumnDef, toColumn: ColumnDef): Boolean {
        return fromColumn.name != toColumn.name &&
            fromColumn.type.equals(toColumn.type, ignoreCase = true) &&
            fromColumn.nullable == toColumn.nullable &&
            fromColumn.defaultValue == toColumn.defaultValue &&
            fromColumn.isPrimaryKey == toColumn.isPrimaryKey
    }

    private fun isSameColumnDefinition(fromColumn: ColumnDef, toColumn: ColumnDef): Boolean {
        return fromColumn.type.equals(toColumn.type, ignoreCase = true) &&
            fromColumn.nullable == toColumn.nullable &&
            fromColumn.defaultValue == toColumn.defaultValue &&
            fromColumn.isPrimaryKey == toColumn.isPrimaryKey
    }

    private fun tableSignature(table: TableSchema): String {
        val columns = table.columns.joinToString("|") { columnSignature(it, includeName = true) }
        val primaryKey = normalizePrimaryKey(table.primaryKey)
        val foreignKeys = normalizeForeignKeys(table.foreignKeys)
        return "$columns::$primaryKey::$foreignKeys"
    }

    private fun columnSignature(column: ColumnDef, includeName: Boolean): String {
        val namePart = if (includeName) "${column.name}:" else ""
        return buildString {
            append(namePart)
            append(column.type.uppercase())
            append(':')
            append(if (column.nullable) "NULL" else "NOT_NULL")
            append(':')
            append(column.defaultValue ?: "")
            append(':')
            append(if (column.isPrimaryKey) "PK" else "NO_PK")
        }
    }

    private fun normalizePrimaryKey(primaryKey: List<String>): String =
        primaryKey.joinToString(",") { it.lowercase() }

    private fun normalizeForeignKeys(foreignKeys: List<ForeignKey>): String =
        foreignKeys
            .map {
                "${it.columns.joinToString(",") { col -> col.lowercase() }}->${it.referencedTable.lowercase()}(${it.referencedColumns.joinToString(",") { col -> col.lowercase() }})"
            }
            .sorted()
            .joinToString("|")

    private fun buildColumnSql(column: ColumnDef): String {
        val parts = mutableListOf(column.name, column.type)
        if (column.isPrimaryKey) parts.add("PRIMARY KEY")
        if (!column.nullable && !column.isPrimaryKey) parts.add("NOT NULL")
        if (column.defaultValue != null) parts.add("DEFAULT ${column.defaultValue}")
        return parts.joinToString(" ")
    }

    private fun generateCreateTable(table: TableSchema): String {
        val sb = StringBuilder()
        sb.append("CREATE TABLE ${table.name} (\n")

        val lines = mutableListOf<String>()
        for (col in table.columns) {
            lines.add("    ${buildColumnSql(col)}")
        }

        val inlinePrimaryKeys = table.columns.filter { it.isPrimaryKey }.map { it.name.lowercase() }.toSet()
        if (table.primaryKey.isNotEmpty() && table.primaryKey.map { it.lowercase() }.toSet() != inlinePrimaryKeys) {
            lines.add("    PRIMARY KEY (${table.primaryKey.joinToString(", ")})")
        }

        for (fk in table.foreignKeys) {
            lines.add("    FOREIGN KEY (${fk.columns.joinToString(", ")}) REFERENCES ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")})")
        }

        sb.append(lines.joinToString(",\n"))
        sb.append("\n);")
        return sb.toString()
    }
}
