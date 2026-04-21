package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

/**
 * Represents the complete database schema state at a specific version.
 */
@Serializable
data class SchemaVersion(
    val version: Int,
    val tables: Map<String, TableSchema>,
    val migrationFile: MigrationFile?,
    val changesSummary: ChangesSummary? = null,
    val risk: MigrationRisk? = null
)

/**
 * Summary of changes that were applied in this migration version.
 */
@Serializable
data class ChangesSummary(
    val tablesAdded: List<String> = emptyList(),
    val tablesRemoved: List<String> = emptyList(),
    val tablesModified: List<String> = emptyList(),
    val columnsAdded: Map<String, List<String>> = emptyMap(),
    val columnsRemoved: Map<String, List<String>> = emptyMap(),
    val removedColumnDefs: Map<String, List<ColumnDef>> = emptyMap(),
    val totalStatements: Int = 0,
    val risk: MigrationRisk? = null
)
