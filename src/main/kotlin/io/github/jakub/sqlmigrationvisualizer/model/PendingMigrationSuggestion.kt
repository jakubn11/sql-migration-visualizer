package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

@Serializable
data class PendingMigrationSuggestion(
    val hasPendingChanges: Boolean = false,
    val generatedSql: String = "",
    val summary: String = "",
    val suggestedVersion: MigrationVersion? = null,
    val suggestedName: String = "",
    val suggestedFileName: String = "",
    val changeHighlights: List<String> = emptyList(),
    val risk: MigrationRisk = MigrationRisk()
)
