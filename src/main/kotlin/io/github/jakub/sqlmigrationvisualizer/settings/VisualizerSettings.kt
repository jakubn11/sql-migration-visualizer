package io.github.jakub.sqlmigrationvisualizer.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import io.github.jakub.sqlmigrationvisualizer.generator.SqlDialect
import kotlinx.serialization.Serializable

@Service(Service.Level.PROJECT)
@State(
    name = "SqlMigrationVisualizerSettings",
    storages = [Storage("SqlMigrationVisualizer.xml")]
)
class VisualizerSettings : PersistentStateComponent<VisualizerSettings.State> {

    @Serializable
    data class ErTablePosition(
        var x: Double = 0.0,
        var y: Double = 0.0
    )

    @Serializable
    data class State(
        var showBaselineInTimeline: Boolean = true,
        var autoExpandTableCards: Boolean = true,
        var defaultTab: String = "timeline",
        var preferredSqlDialect: String = "generic",
        var erShowGrid: Boolean = true,
        var erLayoutColumns: Int = 0,
        var erTablePositions: Map<String, Map<String, ErTablePosition>> = emptyMap(),
        var diffShowUnchangedColumns: Boolean = true,
        var rememberDiffSelections: Boolean = true,
        var lastDiffFromVersion: Int = 0,
        var lastDiffToVersion: Int = 0,
        var searchResultLimit: Int = 20,
        var validateOnRefresh: Boolean = true,
        var suggestPendingMigrationOnSave: Boolean = true,
        var confirmBeforeDeleteMigration: Boolean = true,
        var autoOpenCreatedMigration: Boolean = true,
        var defaultMigrationDirectory: String = "",
        var additionalMigrationDirectories: String = "",
        var migrationFileNamePattern: String = "{version}"
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state.sanitized()
    }

    fun saveErLayout(version: String, positions: Map<String, ErTablePosition>) {
        val versionKey = version.trim()
        if (versionKey.isEmpty()) return

        myState = myState.copy(
            erTablePositions = myState.erTablePositions.toMutableMap().apply {
                this[versionKey] = positions.sanitizedPositions()
            }
        )
    }

    fun clearErLayouts() {
        myState = myState.copy(erTablePositions = emptyMap())
    }

    fun rememberMigrationDirectory(directory: String) {
        myState = myState.copy(defaultMigrationDirectory = directory.trim())
    }

    fun updateDefaultTab(tabId: String) {
        myState = myState.copy(defaultTab = sanitizeDefaultTab(tabId))
    }

    fun updatePreferredSqlDialect(dialectId: String) {
        myState = myState.copy(preferredSqlDialect = SqlDialect.fromId(dialectId).id)
    }

    fun saveDiffSelection(fromVersion: Int, toVersion: Int) {
        myState = myState.copy(
            lastDiffFromVersion = fromVersion.coerceAtLeast(0),
            lastDiffToVersion = toVersion.coerceAtLeast(0)
        )
    }

    fun configuredMigrationDirectories(): List<String> =
        myState.additionalMigrationDirectories
            .split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun State.sanitized(): State =
        copy(
            defaultTab = sanitizeDefaultTab(defaultTab),
            preferredSqlDialect = SqlDialect.fromId(preferredSqlDialect).id,
            erLayoutColumns = erLayoutColumns.coerceIn(0, 10),
            erTablePositions = erTablePositions.mapValues { (_, positions) ->
                positions.sanitizedPositions()
            },
            lastDiffFromVersion = lastDiffFromVersion.coerceAtLeast(0),
            lastDiffToVersion = lastDiffToVersion.coerceAtLeast(0),
            searchResultLimit = searchResultLimit.coerceIn(5, 100),
            defaultMigrationDirectory = defaultMigrationDirectory.trim(),
            additionalMigrationDirectories = additionalMigrationDirectories.trim(),
            migrationFileNamePattern = migrationFileNamePattern.trim().ifBlank { "{version}" }
        )

    private fun Map<String, ErTablePosition>.sanitizedPositions(): Map<String, ErTablePosition> =
        entries
            .mapNotNull { (tableName, position) ->
                val sanitizedName = tableName.trim()
                if (sanitizedName.isEmpty()) {
                    null
                } else {
                    sanitizedName to ErTablePosition(
                        x = position.x.finiteOrZero(),
                        y = position.y.finiteOrZero()
                    )
                }
            }
            .toMap()

    private fun Double.finiteOrZero(): Double =
        if (isFinite()) this else 0.0

    companion object {
        private val DEFAULT_TABS = setOf("timeline", "diff", "er-diagram", "validation")

        private fun sanitizeDefaultTab(tabId: String): String =
            tabId.trim().takeIf { it in DEFAULT_TABS } ?: "timeline"

        fun getInstance(project: Project): VisualizerSettings =
            project.getService(VisualizerSettings::class.java)
    }
}
