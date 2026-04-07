package io.github.jakub.sqlmigrationvisualizer.watcher

import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile

internal data class SchemaChangeSnapshot(
    val migrations: List<MigrationFile>,
    val baselineStatements: List<String>,
    val currentHash: String,
    val migrationFingerprint: String
)

internal data class SchemaChangeCalculation(
    val nextState: SchemaChangePromptService.State,
    val shouldShowPrompt: Boolean,
    val shouldExpireNotification: Boolean
)

internal object SchemaChangePromptStateCalculator {

    fun calculate(
        currentState: SchemaChangePromptService.State,
        snapshot: SchemaChangeSnapshot,
        trigger: SchemaChangePromptService.SuggestionTrigger,
        generatedSql: String?
    ): SchemaChangeCalculation {
        if (snapshot.baselineStatements.isEmpty()) {
            return SchemaChangeCalculation(
                nextState = clearTrackedBaseline(currentState, snapshot.currentHash, snapshot.migrationFingerprint),
                shouldShowPrompt = false,
                shouldExpireNotification = true
            )
        }

        var nextState = currentState

        if (snapshot.migrations.isNotEmpty() && nextState.historyBaselineStatements.isEmpty()) {
            val historyStatements = if (nextState.trackedBaselineStatements.isNotEmpty()) {
                nextState.trackedBaselineStatements
            } else {
                snapshot.baselineStatements
            }
            val historyHash = if (nextState.trackedBaselineHash.isNotBlank()) {
                nextState.trackedBaselineHash
            } else {
                snapshot.currentHash
            }
            nextState = nextState.copy(
                historyBaselineHash = historyHash,
                historyBaselineStatements = historyStatements
            )
        }

        if (nextState.trackedBaselineHash.isBlank()) {
            return SchemaChangeCalculation(
                nextState = initializeTrackedState(nextState, snapshot),
                shouldShowPrompt = false,
                shouldExpireNotification = true
            )
        }

        if (snapshot.migrationFingerprint != nextState.lastMigrationFingerprint) {
            if (
                nextState.lastMigrationFingerprint.isBlank() &&
                snapshot.migrations.isNotEmpty() &&
                nextState.historyBaselineStatements.isEmpty()
            ) {
                nextState = nextState.copy(
                    historyBaselineHash = nextState.trackedBaselineHash,
                    historyBaselineStatements = nextState.trackedBaselineStatements
                )
            }

            nextState = acknowledgeSnapshot(nextState, snapshot.baselineStatements, snapshot.currentHash, snapshot.migrationFingerprint)
        } else {
            nextState = nextState.copy(lastMigrationFingerprint = snapshot.migrationFingerprint)
        }

        if (snapshot.currentHash == nextState.trackedBaselineHash) {
            return SchemaChangeCalculation(
                nextState = clearPendingSuggestion(nextState),
                shouldShowPrompt = false,
                shouldExpireNotification = true
            )
        }

        if (generatedSql == null) {
            return SchemaChangeCalculation(
                nextState = clearPendingSuggestion(nextState),
                shouldShowPrompt = false,
                shouldExpireNotification = true
            )
        }

        nextState = nextState.copy(
            pendingBaselineHash = snapshot.currentHash,
            pendingMigrationSql = generatedSql
        )

        val shouldPrompt = shouldPrompt(
            state = nextState,
            trigger = trigger
        )

        if (shouldPrompt) {
            nextState = nextState.copy(lastNotificationHash = nextState.pendingBaselineHash)
        }

        return SchemaChangeCalculation(
            nextState = nextState,
            shouldShowPrompt = shouldPrompt,
            shouldExpireNotification = false
        )
    }

    private fun initializeTrackedState(
        currentState: SchemaChangePromptService.State,
        snapshot: SchemaChangeSnapshot
    ): SchemaChangePromptService.State {
        val historyStatements = if (snapshot.migrations.isNotEmpty()) {
            snapshot.baselineStatements
        } else {
            currentState.historyBaselineStatements
        }
        val historyHash = if (snapshot.migrations.isNotEmpty()) {
            snapshot.currentHash
        } else {
            currentState.historyBaselineHash
        }
        return currentState.copy(
            trackedBaselineHash = snapshot.currentHash,
            trackedBaselineStatements = snapshot.baselineStatements,
            historyBaselineHash = historyHash,
            historyBaselineStatements = historyStatements,
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = "",
            lastMigrationFingerprint = snapshot.migrationFingerprint
        )
    }

    private fun acknowledgeSnapshot(
        currentState: SchemaChangePromptService.State,
        baselineStatements: List<String>,
        currentHash: String,
        migrationFingerprint: String
    ): SchemaChangePromptService.State =
        currentState.copy(
            trackedBaselineHash = currentHash,
            trackedBaselineStatements = baselineStatements,
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = "",
            lastMigrationFingerprint = migrationFingerprint
        )

    private fun clearPendingSuggestion(
        currentState: SchemaChangePromptService.State
    ): SchemaChangePromptService.State =
        currentState.copy(
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = ""
        )

    private fun clearTrackedBaseline(
        currentState: SchemaChangePromptService.State,
        currentHash: String,
        migrationFingerprint: String
    ): SchemaChangePromptService.State =
        currentState.copy(
            trackedBaselineHash = currentHash,
            trackedBaselineStatements = emptyList(),
            historyBaselineHash = currentHash,
            historyBaselineStatements = emptyList(),
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = "",
            lastMigrationFingerprint = migrationFingerprint
        )

    private fun shouldPrompt(
        state: SchemaChangePromptService.State,
        trigger: SchemaChangePromptService.SuggestionTrigger
    ): Boolean {
        if (trigger != SchemaChangePromptService.SuggestionTrigger.SAVE) return false
        if (state.pendingBaselineHash.isBlank()) return false
        return state.pendingBaselineHash != state.lastNotificationHash
    }
}
