package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

/**
 * Represents a column definition within a table.
 */
@Serializable
data class ColumnDef(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val defaultValue: String? = null,
    val isPrimaryKey: Boolean = false
)

/**
 * Represents a foreign key reference.
 */
@Serializable
data class ForeignKey(
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val constraintName: String? = null
)

/**
 * Represents a single table's complete schema at a given version.
 */
@Serializable
data class TableSchema(
    val name: String,
    val columns: List<ColumnDef>,
    val primaryKey: List<String> = emptyList(),
    val foreignKeys: List<ForeignKey> = emptyList()
)
