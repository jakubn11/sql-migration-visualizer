package io.github.jakub.sqlmigrationvisualizer.parser

import io.github.jakub.sqlmigrationvisualizer.model.*

/**
 * Lightweight SQL parser for common SQL DDL statements.
 * Handles CREATE TABLE, ALTER TABLE, and DROP TABLE to reconstruct
 * schema state across common SQLite, PostgreSQL, and MySQL-style migrations.
 */
class SqlParser {

    companion object {
        private const val OPTIONAL_ONLY = """(?:ONLY\s+)?"""
        private const val IDENTIFIER_SEGMENT = """[`"\[]?[A-Za-z_][\w$]*[`"\]]?"""
        private const val QUALIFIED_IDENTIFIER = """($IDENTIFIER_SEGMENT(?:\s*\.\s*$IDENTIFIER_SEGMENT)*)"""

        // CREATE TABLE patterns
        private val CREATE_TABLE_PATTERN = Regex(
            """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?$QUALIFIED_IDENTIFIER\s*\((.+)\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        // ALTER TABLE patterns
        private val ALTER_ADD_COLUMN_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ADD\s+(?:COLUMN\s+)?(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_RENAME_TABLE_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+RENAME\s+TO\s+$QUALIFIED_IDENTIFIER""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_RENAME_COLUMN_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+RENAME\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+TO\s+$QUALIFIED_IDENTIFIER""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_DROP_COLUMN_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+DROP\s+(?:COLUMN\s+)?$QUALIFIED_IDENTIFIER""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_ALTER_COLUMN_TYPE_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ALTER\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+(?:TYPE|SET\s+DATA\s+TYPE)\s+(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_SET_NOT_NULL_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ALTER\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+SET\s+NOT\s+NULL""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_DROP_NOT_NULL_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ALTER\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+DROP\s+NOT\s+NULL""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_SET_DEFAULT_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ALTER\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+SET\s+DEFAULT\s+(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_DROP_DEFAULT_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ALTER\s+COLUMN\s+$QUALIFIED_IDENTIFIER\s+DROP\s+DEFAULT""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_MODIFY_COLUMN_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+MODIFY\s+(?:COLUMN\s+)?(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_CHANGE_COLUMN_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+CHANGE\s+(?:COLUMN\s+)?$QUALIFIED_IDENTIFIER\s+(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_ADD_FOREIGN_KEY_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ADD\s+(?:CONSTRAINT\s+$QUALIFIED_IDENTIFIER\s+)?FOREIGN\s+KEY\s*\(([^)]+)\)\s*REFERENCES\s+$QUALIFIED_IDENTIFIER(?:\s*\(([^)]+)\))?""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_ADD_PRIMARY_KEY_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+ADD\s+(?:CONSTRAINT\s+$QUALIFIED_IDENTIFIER\s+)?PRIMARY\s+KEY\s*\(([^)]+)\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val ALTER_DROP_FOREIGN_KEY_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+DROP\s+FOREIGN\s+KEY\s+$QUALIFIED_IDENTIFIER""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_DROP_CONSTRAINT_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+DROP\s+CONSTRAINT\s+$QUALIFIED_IDENTIFIER""",
            RegexOption.IGNORE_CASE
        )
        private val ALTER_DROP_PRIMARY_KEY_PATTERN = Regex(
            """ALTER\s+TABLE\s+$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER\s+DROP\s+PRIMARY\s+KEY""",
            RegexOption.IGNORE_CASE
        )

        // DROP TABLE pattern
        private val DROP_TABLE_PATTERN = Regex(
            """DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?$OPTIONAL_ONLY$QUALIFIED_IDENTIFIER(?:\s+(?:CASCADE|RESTRICT))?""",
            RegexOption.IGNORE_CASE
        )

        // Column definition pattern for parsing inside CREATE TABLE / ALTER TABLE
        private val COLUMN_DEF_PATTERN = Regex(
            """^\s*$QUALIFIED_IDENTIFIER\s+(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val COLUMN_TYPE_AND_REST_PATTERN = Regex(
            """^(.+?)(?=\s+(?:NOT\s+NULL|NULL\b|DEFAULT\b|PRIMARY\s+KEY|REFERENCES\b|UNIQUE\b|CHECK\b|CONSTRAINT\b|COLLATE\b|COMMENT\b|GENERATED\b|AUTO_INCREMENT\b|ON\s+UPDATE\b)|$)(.*)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val DEFAULT_VALUE_PATTERN = Regex(
            """DEFAULT\s+(.+?)(?=\s+(?:NOT\s+NULL|NULL\b|PRIMARY\s+KEY|REFERENCES\b|UNIQUE\b|CHECK\b|CONSTRAINT\b|COLLATE\b|COMMENT\b|GENERATED\b|AUTO_INCREMENT\b|ON\s+UPDATE\b)|$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val INLINE_REFERENCES_PATTERN = Regex(
            """REFERENCES\s+$QUALIFIED_IDENTIFIER(?:\s*\(([^)]+)\))?""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        // Foreign key constraint pattern
        private val FOREIGN_KEY_PATTERN = Regex(
            """(?:CONSTRAINT\s+$QUALIFIED_IDENTIFIER\s+)?FOREIGN\s+KEY\s*\(([^)]+)\)\s*REFERENCES\s+$QUALIFIED_IDENTIFIER(?:\s*\(([^)]+)\))?""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        // Primary key constraint pattern (table-level)
        private val PRIMARY_KEY_CONSTRAINT_PATTERN = Regex(
            """PRIMARY\s+KEY\s*\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Build a list of SchemaVersions by parsing baseline schema statements
     * plus sequential versioned migration files.
     */
    fun buildSchemaTimeline(
        baselineStatements: List<String>,
        migrations: List<MigrationFile>
    ): List<SchemaVersion> {
        val versions = mutableListOf<SchemaVersion>()
        val currentSchema = mutableMapOf<String, TableSchema>()

        // Version 0: baseline from schema files
        for (stmt in baselineStatements) {
            applyStatement(stmt, currentSchema)
        }
        versions.add(
            SchemaVersion(
                version = 0,
                tables = currentSchema.toMap(),
                migrationFile = null,
                changesSummary = ChangesSummary(
                    tablesAdded = currentSchema.keys.toList(),
                    totalStatements = baselineStatements.size
                )
            )
        )

        // Apply each migration sequentially
        for (migration in migrations) {
            val prevTables = currentSchema.keys.toSet()
            val prevColumns = currentSchema.mapValues { it.value.columns.map { c -> c.name }.toSet() }
            val prevColumnDefs = currentSchema.mapValues { entry ->
                entry.value.columns.associateBy { it.name }
            }

            for (stmt in migration.statements) {
                applyStatement(stmt, currentSchema)
            }

            val newTables = currentSchema.keys.toSet()
            val tablesAdded = (newTables - prevTables).toList()
            val tablesRemoved = (prevTables - newTables).toList()

            val columnsAdded = mutableMapOf<String, List<String>>()
            val columnsRemoved = mutableMapOf<String, List<String>>()
            val removedColumnDefs = mutableMapOf<String, List<ColumnDef>>()
            val tablesModified = mutableListOf<String>()

            for (tableName in newTables.intersect(prevTables)) {
                val oldCols = prevColumns[tableName] ?: emptySet()
                val newCols = currentSchema[tableName]?.columns?.map { it.name }?.toSet() ?: emptySet()
                val added = (newCols - oldCols).toList()
                val removed = (oldCols - newCols).toList()
                if (added.isNotEmpty()) columnsAdded[tableName] = added
                if (removed.isNotEmpty()) {
                    columnsRemoved[tableName] = removed
                    removedColumnDefs[tableName] = removed.mapNotNull { prevColumnDefs[tableName]?.get(it) }
                }
                if (added.isNotEmpty() || removed.isNotEmpty()) tablesModified.add(tableName)
            }

            versions.add(
                SchemaVersion(
                    version = migration.version,
                    tables = currentSchema.toMap(),
                    migrationFile = migration,
                    changesSummary = ChangesSummary(
                        tablesAdded = tablesAdded,
                        tablesRemoved = tablesRemoved,
                        tablesModified = tablesModified,
                        columnsAdded = columnsAdded,
                        columnsRemoved = columnsRemoved,
                        removedColumnDefs = removedColumnDefs,
                        totalStatements = migration.statements.size
                    )
                )
            )
        }

        return versions
    }

    /**
     * Apply a single SQL DDL statement to the current schema state.
     */
    private fun applyStatement(statement: String, schema: MutableMap<String, TableSchema>) {
        val trimmed = statement.trim()

        // Try CREATE TABLE
        CREATE_TABLE_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val body = match.groupValues[2]
            val table = parseCreateTableBody(tableName, body)
            schema[tableName] = table
            return
        }

        ALTER_ADD_FOREIGN_KEY_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val existing = schema[tableName] ?: return
            val constraintName = match.groupValues[2].takeIf { it.isNotBlank() }?.let(::normalizeIdentifier)
            val columns = splitIdentifierList(match.groupValues[3])
            val referencedTable = normalizeIdentifier(match.groupValues[4])
            val referencedColumns = splitIdentifierList(match.groupValues.getOrElse(5) { "" })
            schema[tableName] = existing.copy(
                foreignKeys = existing.foreignKeys + ForeignKey(columns, referencedTable, referencedColumns, constraintName)
            )
            return
        }

        ALTER_ADD_PRIMARY_KEY_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val existing = schema[tableName] ?: return
            val primaryKey = splitIdentifierList(match.groupValues[3])
            schema[tableName] = existing.copy(
                primaryKey = primaryKey,
                columns = existing.columns.map { column ->
                    column.copy(isPrimaryKey = primaryKey.any { key -> key.equals(column.name, ignoreCase = true) })
                }
            )
            return
        }

        ALTER_DROP_FOREIGN_KEY_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val constraintName = normalizeIdentifier(match.groupValues[2])
            removeForeignKeyByConstraintName(schema, tableName, constraintName)
            return
        }

        ALTER_DROP_CONSTRAINT_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val constraintName = normalizeIdentifier(match.groupValues[2])
            if (!removeForeignKeyByConstraintName(schema, tableName, constraintName)) {
                dropPrimaryKeyIfMatchingConstraint(schema, tableName, constraintName)
            }
            return
        }

        ALTER_DROP_PRIMARY_KEY_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            dropPrimaryKey(schema, tableName)
            return
        }

        // Try ALTER TABLE ADD COLUMN
        ALTER_ADD_COLUMN_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val existing = schema[tableName] ?: return
            val definition = match.groupValues[2].trim()
            val newColumn = parseColumnDefinition(definition) ?: return
            val inlineForeignKey = extractInlineForeignKey(newColumn.name, definition)
            schema[tableName] = existing.copy(
                columns = existing.columns + newColumn,
                foreignKeys = if (inlineForeignKey != null) existing.foreignKeys + inlineForeignKey else existing.foreignKeys
            )
            return
        }

        // Try ALTER TABLE RENAME COLUMN (before RENAME TABLE check to avoid conflicts)
        ALTER_RENAME_COLUMN_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val oldName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val newName = normalizeIdentifier(match.groupValues[3]).substringAfterLast('.')
            renameColumn(schema, tableName, oldName, newName)
            return
        }

        ALTER_DROP_COLUMN_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            dropColumn(schema, tableName, columnName)
            return
        }

        ALTER_ALTER_COLUMN_TYPE_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val newType = cleanType(match.groupValues[3])
            val existing = schema[tableName] ?: return
            schema[tableName] = existing.copy(
                columns = existing.columns.map { col ->
                    if (col.name.equals(columnName, ignoreCase = true)) col.copy(type = newType) else col
                }
            )
            return
        }

        ALTER_SET_NOT_NULL_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val existing = schema[tableName] ?: return
            schema[tableName] = existing.copy(
                columns = existing.columns.map { col ->
                    if (col.name.equals(columnName, ignoreCase = true)) col.copy(nullable = false) else col
                }
            )
            return
        }

        ALTER_DROP_NOT_NULL_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val existing = schema[tableName] ?: return
            schema[tableName] = existing.copy(
                columns = existing.columns.map { col ->
                    if (col.name.equals(columnName, ignoreCase = true)) col.copy(nullable = true) else col
                }
            )
            return
        }

        ALTER_SET_DEFAULT_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val defaultValue = match.groupValues[3].trim().removeSuffix(";")
            val existing = schema[tableName] ?: return
            schema[tableName] = existing.copy(
                columns = existing.columns.map { col ->
                    if (col.name.equals(columnName, ignoreCase = true)) col.copy(defaultValue = defaultValue) else col
                }
            )
            return
        }

        ALTER_DROP_DEFAULT_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val columnName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val existing = schema[tableName] ?: return
            schema[tableName] = existing.copy(
                columns = existing.columns.map { col ->
                    if (col.name.equals(columnName, ignoreCase = true)) col.copy(defaultValue = null) else col
                }
            )
            return
        }

        ALTER_MODIFY_COLUMN_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val definition = match.groupValues[2].trim()
            val updatedColumn = parseColumnDefinition(definition) ?: return
            val inlineForeignKey = extractInlineForeignKey(updatedColumn.name, definition)
            replaceColumn(schema, tableName, updatedColumn.name, updatedColumn, inlineForeignKey)
            return
        }

        ALTER_CHANGE_COLUMN_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            val oldName = normalizeIdentifier(match.groupValues[2]).substringAfterLast('.')
            val definition = match.groupValues[3].trim()
            val updatedColumn = parseColumnDefinition(definition) ?: return
            val inlineForeignKey = extractInlineForeignKey(updatedColumn.name, definition)
            renameColumn(schema, tableName, oldName, updatedColumn.name, updatedColumn, inlineForeignKey)
            return
        }

        // Try ALTER TABLE RENAME TO
        ALTER_RENAME_TABLE_PATTERN.find(trimmed)?.let { match ->
            val oldName = normalizeIdentifier(match.groupValues[1])
            val newName = normalizeIdentifier(match.groupValues[2])

            val existing = schema.remove(oldName) ?: return
            schema[newName] = existing.copy(name = newName)
            updateReferencedTableName(schema, oldName, newName)
            return
        }

        // Try DROP TABLE
        DROP_TABLE_PATTERN.find(trimmed)?.let { match ->
            val tableName = normalizeIdentifier(match.groupValues[1])
            schema.remove(tableName)
            removeForeignKeysReferencingTable(schema, tableName)
            return
        }
    }

    /**
     * Parse the body of a CREATE TABLE statement into a TableSchema.
     */
    private fun parseCreateTableBody(tableName: String, body: String): TableSchema {
        val columns = mutableListOf<ColumnDef>()
        val foreignKeys = mutableListOf<ForeignKey>()
        val primaryKeyColumns = mutableListOf<String>()

        // Split by commas but respect parentheses
        val parts = splitByTopLevelComma(body)

        for (part in parts) {
            val trimmedPart = part.trim()

            val pkMatch = PRIMARY_KEY_CONSTRAINT_PATTERN.find(trimmedPart)
            val fkMatch = FOREIGN_KEY_PATTERN.find(trimmedPart)
            val upperPart = trimmedPart.uppercase()

            if (pkMatch != null) {
                // Table-level PRIMARY KEY constraint
                primaryKeyColumns.addAll(splitIdentifierList(pkMatch.groupValues[1]))
            } else if (fkMatch != null) {
                // FOREIGN KEY constraint
                val constraintName = fkMatch.groupValues[1].takeIf { it.isNotBlank() }?.let(::normalizeIdentifier)
                val fkColumns = splitIdentifierList(fkMatch.groupValues[2])
                val refTable = normalizeIdentifier(fkMatch.groupValues[3])
                val refColumns = splitIdentifierList(fkMatch.groupValues.getOrElse(4) { "" })
                foreignKeys.add(ForeignKey(fkColumns, refTable, refColumns, constraintName))
            } else if (upperPart.startsWith("CONSTRAINT") || upperPart.startsWith("UNIQUE") || upperPart.startsWith("CHECK")) {
                // Skip other constraints
            } else {
                // Parse as column definition
                parseColumnDefinition(trimmedPart)?.let { column ->
                    columns.add(column)
                    if (column.isPrimaryKey) {
                        primaryKeyColumns.add(column.name)
                    }
                    extractInlineForeignKey(column.name, trimmedPart)?.let(foreignKeys::add)
                }
            }
        }

        return TableSchema(
            name = tableName,
            columns = columns,
            primaryKey = primaryKeyColumns,
            foreignKeys = foreignKeys
        )
    }

    /**
     * Splits a string by commas, respecting nested parentheses.
     */
    private fun splitByTopLevelComma(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0

        for (char in input) {
            when {
                char == '(' -> {
                    depth++
                    current.append(char)
                }
                char == ')' -> {
                    depth--
                    current.append(char)
                }
                char == ',' && depth == 0 -> {
                    parts.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
        }

        if (current.isNotBlank()) {
            parts.add(current.toString())
        }

        return parts
    }

    private fun parseColumnDefinition(definition: String): ColumnDef? {
        val match = COLUMN_DEF_PATTERN.find(definition) ?: return null
        val columnName = normalizeIdentifier(match.groupValues[1]).substringAfterLast('.')
        val typeAndRest = match.groupValues[2].trim()
        val typeMatch = COLUMN_TYPE_AND_REST_PATTERN.find(typeAndRest) ?: return null
        val columnType = cleanType(typeMatch.groupValues[1])
        val rest = typeMatch.groupValues.getOrElse(2) { "" }.trim()

        val isPrimary = rest.contains("PRIMARY KEY", ignoreCase = true)
        val isNotNull = rest.contains("NOT NULL", ignoreCase = true) || isPrimary
        val defaultValue = DEFAULT_VALUE_PATTERN.find(rest)?.groupValues?.get(1)?.trim()

        return ColumnDef(
            name = columnName,
            type = columnType,
            nullable = !isNotNull,
            defaultValue = defaultValue,
            isPrimaryKey = isPrimary
        )
    }

    private fun replaceColumn(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        columnName: String,
        updatedColumn: ColumnDef,
        inlineForeignKey: ForeignKey? = null
    ) {
        val existing = schema[tableName] ?: return
        val originalColumn = existing.columns.firstOrNull { it.name.equals(columnName, ignoreCase = true) } ?: return
        val mergedColumn = updatedColumn.copy(isPrimaryKey = updatedColumn.isPrimaryKey || originalColumn.isPrimaryKey)
        val updatedColumns = existing.columns.map { column ->
            if (column.name.equals(columnName, ignoreCase = true)) mergedColumn else column
        }
        schema[tableName] = existing.copy(
            columns = updatedColumns,
            primaryKey = mergePrimaryKeyMembership(existing.primaryKey, originalColumn.name, mergedColumn.name, mergedColumn.isPrimaryKey),
            foreignKeys = updateColumnForeignKeys(existing, originalColumn.name, mergedColumn.name, inlineForeignKey)
        )
        if (!originalColumn.name.equals(mergedColumn.name, ignoreCase = true)) {
            updateReferencedColumnName(schema, tableName, originalColumn.name, mergedColumn.name)
        }
    }

    private fun renameColumn(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        oldName: String,
        newName: String,
        replacement: ColumnDef? = null,
        inlineForeignKey: ForeignKey? = null
    ) {
        val existing = schema[tableName] ?: return
        val originalColumn = existing.columns.firstOrNull { it.name.equals(oldName, ignoreCase = true) } ?: return
        val renamedColumn = replacement?.copy(isPrimaryKey = replacement.isPrimaryKey || originalColumn.isPrimaryKey)
            ?: originalColumn.copy(name = newName)
        val updatedColumns = existing.columns.map { column ->
            if (column.name.equals(oldName, ignoreCase = true)) renamedColumn else column
        }
        schema[tableName] = existing.copy(
            columns = updatedColumns,
            primaryKey = mergePrimaryKeyMembership(existing.primaryKey, oldName, renamedColumn.name, renamedColumn.isPrimaryKey),
            foreignKeys = updateColumnForeignKeys(existing, oldName, renamedColumn.name, inlineForeignKey)
        )
        updateReferencedColumnName(schema, tableName, oldName, renamedColumn.name)
    }

    private fun dropColumn(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        columnName: String
    ) {
        val existing = schema[tableName] ?: return
        schema[tableName] = existing.copy(
            columns = existing.columns.filterNot { it.name.equals(columnName, ignoreCase = true) },
            primaryKey = existing.primaryKey.filterNot { it.equals(columnName, ignoreCase = true) },
            foreignKeys = existing.foreignKeys.filterNot { foreignKey ->
                foreignKey.columns.any { it.equals(columnName, ignoreCase = true) } ||
                    (foreignKey.referencedTable.equals(tableName, ignoreCase = true) &&
                        foreignKey.referencedColumns.any { it.equals(columnName, ignoreCase = true) })
            }
        )
        removeForeignKeysReferencingColumn(schema, tableName, columnName)
    }

    private fun extractInlineForeignKey(columnName: String, definition: String): ForeignKey? {
        val match = INLINE_REFERENCES_PATTERN.find(definition) ?: return null
        return ForeignKey(
            columns = listOf(columnName),
            referencedTable = normalizeIdentifier(match.groupValues[1]),
            referencedColumns = splitIdentifierList(match.groupValues.getOrElse(2) { "" })
        )
    }

    private fun splitIdentifierList(input: String): List<String> =
        input.split(",")
            .map { normalizeIdentifier(it) }
            .filter { it.isNotBlank() }

    private fun mergePrimaryKeyMembership(
        currentPrimaryKey: List<String>,
        oldName: String,
        newName: String,
        isPrimaryKey: Boolean
    ): List<String> {
        val renamed = currentPrimaryKey.map { key ->
            if (key.equals(oldName, ignoreCase = true)) newName else key
        }
        val updated = if (isPrimaryKey && renamed.none { it.equals(newName, ignoreCase = true) }) {
            renamed + newName
        } else {
            renamed
        }
        return updated.distinctBy { it.lowercase() }
    }

    private fun updateColumnForeignKeys(
        table: TableSchema,
        oldName: String,
        newName: String,
        inlineForeignKey: ForeignKey?
    ): List<ForeignKey> {
        val retainedForeignKeys = table.foreignKeys.mapNotNull { foreignKey ->
            val updatedColumns = foreignKey.columns.map { column ->
                if (column.equals(oldName, ignoreCase = true)) newName else column
            }
            val updatedReferencedColumns = if (foreignKey.referencedTable.equals(table.name, ignoreCase = true)) {
                foreignKey.referencedColumns.map { column ->
                    if (column.equals(oldName, ignoreCase = true)) newName else column
                }
            } else {
                foreignKey.referencedColumns
            }
            val touchesColumn = updatedColumns.any { it.equals(newName, ignoreCase = true) }
            when {
                updatedColumns.isEmpty() || updatedReferencedColumns.isEmpty() -> null
                inlineForeignKey != null && touchesColumn -> null
                else -> foreignKey.copy(columns = updatedColumns, referencedColumns = updatedReferencedColumns)
            }
        }
        return if (inlineForeignKey != null) retainedForeignKeys + inlineForeignKey else retainedForeignKeys
    }

    private fun updateReferencedTableName(
        schema: MutableMap<String, TableSchema>,
        oldTableName: String,
        newTableName: String
    ) {
        schema.replaceAll { _, table ->
            table.copy(
                foreignKeys = table.foreignKeys.map { foreignKey ->
                    if (foreignKey.referencedTable.equals(oldTableName, ignoreCase = true)) {
                        foreignKey.copy(referencedTable = newTableName)
                    } else {
                        foreignKey
                    }
                }
            )
        }
    }

    private fun updateReferencedColumnName(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        oldColumnName: String,
        newColumnName: String
    ) {
        schema.replaceAll { _, table ->
            table.copy(
                foreignKeys = table.foreignKeys.map { foreignKey ->
                    if (!foreignKey.referencedTable.equals(tableName, ignoreCase = true)) {
                        foreignKey
                    } else {
                        foreignKey.copy(
                            referencedColumns = foreignKey.referencedColumns.map { referencedColumn ->
                                if (referencedColumn.equals(oldColumnName, ignoreCase = true)) newColumnName else referencedColumn
                            }
                        )
                    }
                }
            )
        }
    }

    private fun removeForeignKeysReferencingTable(
        schema: MutableMap<String, TableSchema>,
        tableName: String
    ) {
        schema.replaceAll { _, table ->
            table.copy(
                foreignKeys = table.foreignKeys.filterNot { foreignKey ->
                    foreignKey.referencedTable.equals(tableName, ignoreCase = true)
                }
            )
        }
    }

    private fun removeForeignKeysReferencingColumn(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        columnName: String
    ) {
        schema.replaceAll { _, table ->
            table.copy(
                foreignKeys = table.foreignKeys.filterNot { foreignKey ->
                    foreignKey.referencedTable.equals(tableName, ignoreCase = true) &&
                        foreignKey.referencedColumns.any { it.equals(columnName, ignoreCase = true) }
                }
            )
        }
    }

    private fun removeForeignKeyByConstraintName(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        constraintName: String
    ): Boolean {
        val existing = schema[tableName] ?: return false
        val updatedForeignKeys = existing.foreignKeys.filterNot { foreignKey ->
            foreignKey.constraintName?.equals(constraintName, ignoreCase = true) == true
        }
        if (updatedForeignKeys.size == existing.foreignKeys.size) {
            return false
        }
        schema[tableName] = existing.copy(foreignKeys = updatedForeignKeys)
        return true
    }

    private fun dropPrimaryKey(
        schema: MutableMap<String, TableSchema>,
        tableName: String
    ) {
        val existing = schema[tableName] ?: return
        schema[tableName] = existing.copy(
            primaryKey = emptyList(),
            columns = existing.columns.map { column -> column.copy(isPrimaryKey = false) }
        )
    }

    private fun dropPrimaryKeyIfMatchingConstraint(
        schema: MutableMap<String, TableSchema>,
        tableName: String,
        constraintName: String
    ) {
        val normalizedConstraint = constraintName.lowercase()
        if (normalizedConstraint == "primary" || normalizedConstraint.endsWith("_pkey")) {
            dropPrimaryKey(schema, tableName)
        }
    }

    private fun normalizeIdentifier(identifier: String): String =
        identifier
            .trim()
            .split(".")
            .map { segment ->
                segment.trim()
                    .removeSurrounding("`")
                    .removeSurrounding("\"")
                    .removeSurrounding("[", "]")
            }
            .filter { it.isNotBlank() }
            .joinToString(".")

    private fun cleanType(type: String): String =
        type.trim()
            .removeSuffix(",")
            .replace(Regex("""\s+"""), " ")
            .uppercase()
}
