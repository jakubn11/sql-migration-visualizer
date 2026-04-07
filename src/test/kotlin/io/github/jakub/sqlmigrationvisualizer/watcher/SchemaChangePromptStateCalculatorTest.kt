package io.github.jakub.sqlmigrationvisualizer.watcher

import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaChangePromptStateCalculatorTest {

    @Test
    fun `initial snapshot seeds tracked state without pending migration`() {
        val calculation = SchemaChangePromptStateCalculator.calculate(
            currentState = SchemaChangePromptService.State(),
            snapshot = snapshot(
                migrations = emptyList(),
                baselineStatements = listOf("CREATE TABLE users (id INTEGER PRIMARY KEY)")
            ),
            trigger = SchemaChangePromptService.SuggestionTrigger.INITIALIZE,
            generatedSql = null
        )

        assertEquals("baseline-1", calculation.nextState.trackedBaselineHash)
        assertEquals("", calculation.nextState.pendingMigrationSql)
        assertFalse(calculation.shouldShowPrompt)
    }

    @Test
    fun `save with schema changes stores pending migration and prompts once`() {
        val currentState = SchemaChangePromptService.State(
            trackedBaselineHash = "baseline-1",
            trackedBaselineStatements = listOf("CREATE TABLE users (id INTEGER PRIMARY KEY)"),
            lastMigrationFingerprint = "mig-0"
        )

        val calculation = SchemaChangePromptStateCalculator.calculate(
            currentState = currentState,
            snapshot = snapshot(
                migrations = emptyList(),
                baselineStatements = listOf(
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"
                ),
                currentHash = "baseline-2",
                migrationFingerprint = "mig-0"
            ),
            trigger = SchemaChangePromptService.SuggestionTrigger.SAVE,
            generatedSql = "ALTER TABLE users ADD COLUMN name TEXT NOT NULL;"
        )

        assertEquals("baseline-2", calculation.nextState.pendingBaselineHash)
        assertTrue(calculation.nextState.pendingMigrationSql.contains("ADD COLUMN"))
        assertTrue(calculation.shouldShowPrompt)
    }

    @Test
    fun `repeat save for same pending change does not re-prompt`() {
        val currentState = SchemaChangePromptService.State(
            trackedBaselineHash = "baseline-1",
            trackedBaselineStatements = listOf("CREATE TABLE users (id INTEGER PRIMARY KEY)"),
            pendingBaselineHash = "baseline-2",
            pendingMigrationSql = "ALTER TABLE users ADD COLUMN email TEXT;",
            lastNotificationHash = "baseline-2",
            lastMigrationFingerprint = "mig-0"
        )

        val calculation = SchemaChangePromptStateCalculator.calculate(
            currentState = currentState,
            snapshot = snapshot(
                migrations = emptyList(),
                baselineStatements = listOf(
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)"
                ),
                currentHash = "baseline-2",
                migrationFingerprint = "mig-0"
            ),
            trigger = SchemaChangePromptService.SuggestionTrigger.SAVE,
            generatedSql = "ALTER TABLE users ADD COLUMN email TEXT;"
        )

        assertFalse(calculation.shouldShowPrompt)
        assertEquals("baseline-2", calculation.nextState.lastNotificationHash)
    }

    @Test
    fun `new migration fingerprint acknowledges snapshot and clears pending state`() {
        val currentState = SchemaChangePromptService.State(
            trackedBaselineHash = "baseline-1",
            trackedBaselineStatements = listOf("CREATE TABLE users (id INTEGER PRIMARY KEY)"),
            pendingBaselineHash = "baseline-2",
            pendingMigrationSql = "ALTER TABLE users ADD COLUMN email TEXT;",
            lastNotificationHash = "baseline-2",
            lastMigrationFingerprint = "mig-0"
        )

        val calculation = SchemaChangePromptStateCalculator.calculate(
            currentState = currentState,
            snapshot = snapshot(
                migrations = listOf(
                    MigrationFile(
                        version = 1,
                        filePath = "/tmp/1.sqm",
                        fileName = "1.sqm",
                        statements = listOf("ALTER TABLE users ADD COLUMN email TEXT"),
                        rawContent = "ALTER TABLE users ADD COLUMN email TEXT;"
                    )
                ),
                baselineStatements = listOf(
                    "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)"
                ),
                currentHash = "baseline-2",
                migrationFingerprint = "mig-1"
            ),
            trigger = SchemaChangePromptService.SuggestionTrigger.SAVE,
            generatedSql = null
        )

        assertEquals("baseline-2", calculation.nextState.trackedBaselineHash)
        assertEquals("", calculation.nextState.pendingBaselineHash)
        assertEquals("", calculation.nextState.pendingMigrationSql)
        assertTrue(calculation.shouldExpireNotification)
    }

    private fun snapshot(
        migrations: List<MigrationFile>,
        baselineStatements: List<String>,
        currentHash: String = "baseline-1",
        migrationFingerprint: String = "mig-0"
    ) = SchemaChangeSnapshot(
        migrations = migrations,
        baselineStatements = baselineStatements,
        currentHash = currentHash,
        migrationFingerprint = migrationFingerprint
    )
}
