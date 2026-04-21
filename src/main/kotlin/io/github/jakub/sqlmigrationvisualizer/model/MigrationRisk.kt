package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

@Serializable
enum class MigrationRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

@Serializable
data class MigrationRiskItem(
    val level: MigrationRiskLevel,
    val title: String,
    val detail: String,
    val tableName: String? = null,
    val columnName: String? = null
)

@Serializable
data class MigrationRisk(
    val level: MigrationRiskLevel = MigrationRiskLevel.LOW,
    val score: Int = 0,
    val headline: String = "Low-risk additive change",
    val items: List<MigrationRiskItem> = emptyList()
)
