package io.github.jakub.sqlmigrationvisualizer.validator

import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.ValidationIssue
import io.github.jakub.sqlmigrationvisualizer.model.ValidationIssueCode
import io.github.jakub.sqlmigrationvisualizer.model.ValidationResult
import io.github.jakub.sqlmigrationvisualizer.model.ValidationSeverity

/**
 * Validates migration files for consistency, gaps, duplicates,
 * and semantic correctness.
 */
class MigrationValidator {

    /**
     * Validate a list of migration files and schema versions.
     */
    fun validate(
        migrations: List<MigrationFile>,
        schemaVersions: List<SchemaVersion>
    ): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        if (migrations.isEmpty()) {
            return ValidationResult(
                isValid = true,
                issues = listOf(
                    ValidationIssue(
                        severity = ValidationSeverity.INFO,
                        message = "No versioned migration files found in the project.",
                        code = ValidationIssueCode.NO_MIGRATIONS,
                        explanation = "The visualizer can still inspect your baseline schema, but migration-only checks cannot run until at least one numbered migration file exists.",
                        suggestedFix = "Create your first migration when you are ready to start versioning schema changes."
                    )
                ),
                summary = "No migrations to validate."
            )
        }

        checkVersionGaps(migrations, issues)
        checkDuplicateVersions(migrations, issues)
        checkEmptyMigrations(migrations, issues)
        validateAlterTableStatements(schemaVersions, issues)
        checkSuspiciousPatterns(migrations, issues)
        validateForeignKeys(schemaVersions, issues)

        val errorCount = issues.count { it.severity == ValidationSeverity.ERROR }
        val warningCount = issues.count { it.severity == ValidationSeverity.WARNING }
        val infoCount = issues.count { it.severity == ValidationSeverity.INFO }

        val summary = buildString {
            append("Validated ${migrations.size} migration(s). ")
            if (errorCount > 0) append("$errorCount error(s), ")
            if (warningCount > 0) append("$warningCount warning(s), ")
            if (infoCount > 0) append("$infoCount info message(s).")
            if (errorCount == 0 && warningCount == 0) append("All checks passed ✓")
        }

        return ValidationResult(
            isValid = errorCount == 0,
            issues = issues,
            summary = summary
        )
    }

    private fun checkVersionGaps(
        migrations: List<MigrationFile>,
        issues: MutableList<ValidationIssue>
    ) {
        val versions = migrations.map { it.version }.sorted()
        if (versions.isEmpty()) return

        for (i in 0 until versions.size - 1) {
            val current = versions[i]
            val next = versions[i + 1]
            if (next - current > 1) {
                val missing = (current + 1 until next).toList()
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        message = "Missing migration version(s): ${missing.joinToString(", ")}",
                        code = ValidationIssueCode.VERSION_GAP,
                        details = "Gap detected between version $current and $next. This visualizer assumes sequential numeric versions for upgrade order.",
                        explanation = "When a numeric version is skipped, it becomes harder to reason about upgrade order and schema history across environments.",
                        suggestedFix = "Create the missing version file(s), or rename later migrations so the numbering is continuous again.",
                        missingVersions = missing,
                        contextVersions = listOf(current, next)
                    )
                )
            }
        }
    }

    private fun checkDuplicateVersions(
        migrations: List<MigrationFile>,
        issues: MutableList<ValidationIssue>
    ) {
        val grouped = migrations.groupBy { it.version }
        for ((version, files) in grouped) {
            if (files.size > 1) {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.ERROR,
                        message = "Duplicate migration version: $version",
                        version = version,
                        code = ValidationIssueCode.DUPLICATE_VERSION,
                        details = "Found ${files.size} files for version $version:\n" +
                            files.joinToString("\n") { "  • ${it.filePath}" },
                        explanation = "Two migration files with the same version number compete for the same upgrade step, so your migration order becomes ambiguous.",
                        suggestedFix = "Keep exactly one file for this version, or rename the extras to the next free version numbers.",
                        relatedFilePaths = files.map { it.filePath }
                    )
                )
            }
        }
    }

    private fun checkEmptyMigrations(
        migrations: List<MigrationFile>,
        issues: MutableList<ValidationIssue>
    ) {
        for (migration in migrations) {
            if (migration.statements.isEmpty()) {
                issues.add(
                    ValidationIssue(
                        severity = ValidationSeverity.WARNING,
                        message = "Empty migration file: ${migration.fileName}",
                        version = migration.version,
                        filePath = migration.filePath,
                        code = ValidationIssueCode.EMPTY_MIGRATION,
                        details = "This migration file contains no SQL statements.",
                        explanation = "An empty migration is sometimes intentional as a checkpoint, but it can also mean a migration was started and never finished.",
                        suggestedFix = "Either add the missing SQL statements or remove the file if that version should not exist."
                    )
                )
            }
        }
    }

    private fun validateAlterTableStatements(
        schemaVersions: List<SchemaVersion>,
        issues: MutableList<ValidationIssue>
    ) {
        for (i in 1 until schemaVersions.size) {
            val prevSchema = schemaVersions[i - 1]
            val currentVersion = schemaVersions[i]
            val migration = currentVersion.migrationFile ?: continue

            for (stmt in migration.statements) {
                val alterMatch = Regex(
                    """ALTER\s+TABLE\s+[`"\[]?(\w+)[`"\]]?""",
                    RegexOption.IGNORE_CASE
                ).find(stmt)

                if (alterMatch != null) {
                    val tableName = alterMatch.groupValues[1]
                    if (!prevSchema.tables.containsKey(tableName)) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                message = "ALTER TABLE references non-existent table '$tableName'",
                                version = migration.version,
                                filePath = migration.filePath,
                                code = ValidationIssueCode.ALTER_TABLE_TARGET_MISSING,
                                details = "In ${migration.fileName}: Table '$tableName' does not exist at version ${prevSchema.version}. Available tables: ${prevSchema.tables.keys.joinToString(", ")}",
                                explanation = "ALTER TABLE only works when the referenced table already exists in the schema snapshot that precedes this migration.",
                                suggestedFix = "Check the table name, make sure the create-table migration runs earlier, or update this migration to target the correct table.",
                                tableName = tableName,
                                contextVersions = listOf(prevSchema.version, currentVersion.version)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun checkSuspiciousPatterns(
        migrations: List<MigrationFile>,
        issues: MutableList<ValidationIssue>
    ) {
        for (migration in migrations) {
            for (stmt in migration.statements) {
                val upper = stmt.trim().uppercase()

                if (upper.startsWith("BEGIN") || upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")) {
                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.WARNING,
                            message = "Transaction statement found in ${migration.fileName}",
                            version = migration.version,
                            filePath = migration.filePath,
                            code = ValidationIssueCode.TRANSACTION_STATEMENT,
                            details = "Some migration runners handle transactions automatically. Manual transaction statements may cause issues depending on your tooling.",
                            explanation = "Wrapping migrations in manual BEGIN/COMMIT blocks can conflict with framework-managed transaction behavior or make failures harder to recover from cleanly.",
                            suggestedFix = "Remove the manual transaction statement unless you have a very specific reason to keep it."
                        )
                    )
                }

                if (upper.startsWith("DROP TABLE")) {
                    val droppedTable = Regex(
                        """DROP\s+TABLE(?:\s+IF\s+EXISTS)?\s+[`"\[]?(\w+)[`"\]]?""",
                        RegexOption.IGNORE_CASE
                    ).find(stmt)?.groupValues?.getOrNull(1)

                    issues.add(
                        ValidationIssue(
                            severity = ValidationSeverity.INFO,
                            message = "DROP TABLE statement in ${migration.fileName}",
                            version = migration.version,
                            filePath = migration.filePath,
                            code = ValidationIssueCode.DROP_TABLE_STATEMENT,
                            details = "Dropping tables in migrations is valid but destructive. Ensure this is intentional.",
                            explanation = "DROP TABLE permanently removes the table and its data for everyone upgrading through this migration.",
                            suggestedFix = "Double-check that the table is no longer needed, or replace the drop with a safer data-preserving strategy.",
                            tableName = droppedTable
                        )
                    )
                }
            }
        }
    }

    private fun validateForeignKeys(
        schemaVersions: List<SchemaVersion>,
        issues: MutableList<ValidationIssue>
    ) {
        for (schemaVersion in schemaVersions) {
            for ((tableName, table) in schemaVersion.tables) {
                for (fk in table.foreignKeys) {
                    if (!schemaVersion.tables.containsKey(fk.referencedTable)) {
                        issues.add(
                            ValidationIssue(
                                severity = ValidationSeverity.ERROR,
                                message = "Foreign key in '$tableName' references non-existent table '${fk.referencedTable}'",
                                version = schemaVersion.version,
                                code = ValidationIssueCode.FOREIGN_KEY_TARGET_MISSING,
                                details = "Column(s) ${fk.columns.joinToString(", ")} reference ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")}), but table '${fk.referencedTable}' does not exist at version ${schemaVersion.version}.",
                                explanation = "Foreign keys can only reference tables that exist in the same schema snapshot. If the target table is missing, inserts and updates will fail once the constraint is enforced.",
                                suggestedFix = "Create the referenced table earlier, rename the foreign key target, or remove the constraint until the dependency exists.",
                                tableName = tableName,
                                contextVersions = listOf(schemaVersion.version)
                            )
                        )
                    }
                }
            }
        }
    }
}
