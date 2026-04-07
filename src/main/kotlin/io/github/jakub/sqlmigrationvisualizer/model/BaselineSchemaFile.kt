package io.github.jakub.sqlmigrationvisualizer.model

data class BaselineSchemaFile(
    val filePath: String,
    val fileName: String,
    val createStatements: List<String>,
    val rawContent: String
)
