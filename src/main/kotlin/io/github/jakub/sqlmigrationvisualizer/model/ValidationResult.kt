package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.Serializable

/**
 * Severity levels for validation issues.
 */
@Serializable
enum class ValidationSeverity {
    ERROR,
    WARNING,
    INFO
}

@Serializable
enum class ValidationIssueCode {
    NO_MIGRATIONS,
    VERSION_GAP,
    DUPLICATE_VERSION,
    EMPTY_MIGRATION,
    ALTER_TABLE_TARGET_MISSING,
    TRANSACTION_STATEMENT,
    DROP_TABLE_STATEMENT,
    FOREIGN_KEY_TARGET_MISSING
}

/**
 * A single validation issue found during migration analysis.
 */
@Serializable
data class ValidationIssue(
    val severity: ValidationSeverity,
    val message: String,
    val version: Int? = null,
    val filePath: String? = null,
    val details: String? = null,
    val code: ValidationIssueCode? = null,
    val explanation: String? = null,
    val suggestedFix: String? = null,
    val relatedFilePaths: List<String> = emptyList(),
    val missingVersions: List<Int> = emptyList(),
    val contextVersions: List<Int> = emptyList(),
    val tableName: String? = null,
    val columnName: String? = null
)

/**
 * Complete result of migration validation.
 */
@Serializable
data class ValidationResult(
    val isValid: Boolean,
    val issues: List<ValidationIssue>,
    val summary: String
)
