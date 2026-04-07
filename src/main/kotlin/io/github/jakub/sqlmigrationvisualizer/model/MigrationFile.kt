package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

/**
 * Represents a single versioned migration file discovered in the project.
 */
@Serializable
data class MigrationFile(
    val version: Int,
    val filePath: String,
    val fileName: String,
    val statements: List<String>,
    val rawContent: String
)
