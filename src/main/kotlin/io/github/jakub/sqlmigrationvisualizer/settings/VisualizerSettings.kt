package io.github.jakub.sqlmigrationvisualizer.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable

@Service(Service.Level.PROJECT)
@State(
    name = "SqlMigrationVisualizerSettings",
    storages = [Storage("SqlMigrationVisualizer.xml")]
)
class VisualizerSettings : PersistentStateComponent<VisualizerSettings.State> {

    @Serializable
    data class ErTablePosition(
        val x: Double,
        val y: Double
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
        myState = state
    }

    fun saveErLayout(version: String, positions: Map<String, ErTablePosition>) {
        myState = myState.copy(
            erTablePositions = myState.erTablePositions.toMutableMap().apply {
                this[version] = positions
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
        myState = myState.copy(defaultTab = tabId)
    }

    fun updatePreferredSqlDialect(dialectId: String) {
        myState = myState.copy(preferredSqlDialect = dialectId)
    }

    fun saveDiffSelection(fromVersion: Int, toVersion: Int) {
        myState = myState.copy(
            lastDiffFromVersion = fromVersion,
            lastDiffToVersion = toVersion
        )
    }

    fun configuredMigrationDirectories(): List<String> =
        myState.additionalMigrationDirectories
            .split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        fun getInstance(project: Project): VisualizerSettings =
            project.getService(VisualizerSettings::class.java)
    }
}
