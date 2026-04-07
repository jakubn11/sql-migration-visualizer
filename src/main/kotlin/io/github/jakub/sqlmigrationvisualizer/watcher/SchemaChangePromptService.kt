package io.github.jakub.sqlmigrationvisualizer.watcher

import io.github.jakub.sqlmigrationvisualizer.MigrationVisualizerToolWindowFactory
import io.github.jakub.sqlmigrationvisualizer.generator.MigrationGenerator
import io.github.jakub.sqlmigrationvisualizer.generator.SqlDialect
import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import io.github.jakub.sqlmigrationvisualizer.model.PendingMigrationSuggestion
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.parser.SqlParser
import io.github.jakub.sqlmigrationvisualizer.services.ProjectSchemaSnapshotService
import io.github.jakub.sqlmigrationvisualizer.settings.VisualizerSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Alarm
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
@State(
    name = "SqlDelightSchemaChangePromptState",
    storages = [Storage("SqlDelightSchemaChangePrompt.xml")]
)
class SchemaChangePromptService(
    private val project: Project
) : PersistentStateComponent<SchemaChangePromptService.State>, Disposable {

    data class State(
        var trackedBaselineHash: String = "",
        var trackedBaselineStatements: List<String> = emptyList(),
        var historyBaselineHash: String = "",
        var historyBaselineStatements: List<String> = emptyList(),
        var pendingBaselineHash: String = "",
        var pendingMigrationSql: String = "",
        var lastNotificationHash: String = "",
        var lastMigrationFingerprint: String = ""
    )

    private data class Snapshot(
        val migrations: List<MigrationFile>,
        val baselineStatements: List<String>
    )

    enum class SuggestionTrigger {
        INITIALIZE,
        SAVE
    }

    private var myState = State()
    private val parser = SqlParser()
    private val generator = MigrationGenerator()
    private val snapshotService = project.getService(ProjectSchemaSnapshotService::class.java)
    private val settings = VisualizerSettings.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var activeNotification: Notification? = null

    init {
        initializeSnapshot()

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            AnActionListener.TOPIC,
            object : AnActionListener.Adapter() {
                override fun afterActionPerformed(
                    action: AnAction,
                    dataContext: DataContext,
                    event: AnActionEvent
                ) {
                    if (event.project != null && event.project != project) return

                    val actionId = ActionManager.getInstance().getId(action)
                    if (settings.state.suggestPendingMigrationOnSave &&
                        (actionId == "SaveAll" || actionId == "SaveDocument")
                    ) {
                        scheduleCheck(SuggestionTrigger.SAVE)
                    }
                }
            }
        )
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    private fun initializeSnapshot() {
        scheduleCheck(SuggestionTrigger.INITIALIZE)
    }

    fun checkPendingSuggestionAfterSave() {
        scheduleCheck(SuggestionTrigger.SAVE)
    }

    fun dismissPendingSuggestion() {
        val snapshot = readSnapshot()

        val currentHash = hashStatements(snapshot.baselineStatements)
        val migrationFingerprint = hashMigrations(snapshot.migrations)

        if (snapshot.baselineStatements.isEmpty()) {
            clearPendingSuggestion()
            return
        }

        acknowledgeCurrentSnapshot(
            baselineStatements = snapshot.baselineStatements,
            currentHash = currentHash,
            migrationFingerprint = migrationFingerprint
        )
    }

    fun resetCachedBaseline() {
        myState = State()
        snapshotService.invalidate()
        activeNotification?.expire()
        activeNotification = null
        pushPendingSuggestionToPanel()
        scheduleCheck(SuggestionTrigger.INITIALIZE)
    }

    fun getPendingMigrationSuggestion(): PendingMigrationSuggestion {
        if (myState.pendingBaselineHash.isBlank() || myState.pendingMigrationSql.isBlank()) {
            return PendingMigrationSuggestion()
        }

        val hasExistingMigration = snapshotService.getSnapshot().migrations.isNotEmpty()
        val summary = if (hasExistingMigration) {
            "Schema changes are ready to be saved as your next migration."
        } else {
            "Schema changes are ready to become your first migration."
        }

        return PendingMigrationSuggestion(
            hasPendingChanges = true,
            generatedSql = myState.pendingMigrationSql,
            summary = summary
        )
    }

    fun getTimelineBaselineStatements(hasMigrations: Boolean): List<String> {
        if (!hasMigrations) return emptyList()
        if (myState.historyBaselineStatements.isNotEmpty()) {
            return myState.historyBaselineStatements
        }
        if (myState.trackedBaselineStatements.isNotEmpty()) {
            return myState.trackedBaselineStatements
        }
        return emptyList()
    }

    private fun scheduleCheck(trigger: SuggestionTrigger) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            checkForSchemaChanges(trigger)
        }, 500)
    }

    private fun checkForSchemaChanges(trigger: SuggestionTrigger) {
        if (project.isDisposed) return

        val snapshot = readSnapshot()
        val calculation = SchemaChangePromptStateCalculator.calculate(
            currentState = myState,
            snapshot = SchemaChangeSnapshot(
                migrations = snapshot.migrations,
                baselineStatements = snapshot.baselineStatements,
                currentHash = hashStatements(snapshot.baselineStatements),
                migrationFingerprint = hashMigrations(snapshot.migrations)
            ),
            trigger = trigger,
            generatedSql = if (snapshot.baselineStatements.isNotEmpty()) {
                buildMigration(myState.trackedBaselineStatements, snapshot.baselineStatements)
            } else {
                null
            }
        )

        myState = calculation.nextState

        if (calculation.shouldExpireNotification) {
            activeNotification?.expire()
            activeNotification = null
        }

        pushPendingSuggestionToPanel()

        if (calculation.shouldShowPrompt) {
            showGeneratePrompt()
        }
    }

    private fun buildMigration(
        previousStatements: List<String>,
        currentStatements: List<String>
    ): String? {
        val fromVersion = parser.buildSchemaTimeline(previousStatements, emptyList()).firstOrNull()
            ?: SchemaVersion(0, emptyMap(), null)
        val toVersion = parser.buildSchemaTimeline(currentStatements, emptyList()).firstOrNull()
            ?: SchemaVersion(0, emptyMap(), null)

        val sql = generator.generateMigration(fromVersion, toVersion, SqlDialect.fromId(settings.state.preferredSqlDialect))
        return if (sql.startsWith("-- No changes detected")) null else sql
    }

    private fun showGeneratePrompt() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            activeNotification?.expire()
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("SQL Migration Visualizer")
                .createNotification(
                    "Suggested migration ready",
                    "We noticed schema changes in your SQL schema files. Review them and create the next migration when you're ready.",
                    NotificationType.INFORMATION
                )

            notification.addAction(NotificationAction.createSimple("Open Visualizer") {
                notification.expire()
                ToolWindowManager.getInstance(project)
                    .getToolWindow("SQL Migrations")
                    ?.show {
                        MigrationVisualizerToolWindowFactory.getPanel(project)?.openPendingMigrationComposer()
                    }
            })
            notification.addAction(NotificationAction.createSimple("Not now") {
                notification.expire()
            })

            activeNotification = notification
            notification.notify(project)
        }
    }

    private fun acknowledgeCurrentSnapshot(
        baselineStatements: List<String>,
        currentHash: String,
        migrationFingerprint: String
    ) {
        myState = myState.copy(
            trackedBaselineHash = currentHash,
            trackedBaselineStatements = baselineStatements,
            historyBaselineHash = myState.historyBaselineHash,
            historyBaselineStatements = myState.historyBaselineStatements,
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = "",
            lastMigrationFingerprint = migrationFingerprint
        )
        activeNotification?.expire()
        activeNotification = null
        pushPendingSuggestionToPanel()
    }

    private fun clearPendingSuggestion() {
        val hasPendingState = myState.pendingBaselineHash.isNotBlank() ||
            myState.pendingMigrationSql.isNotBlank() ||
            myState.lastNotificationHash.isNotBlank() ||
            activeNotification != null
        if (!hasPendingState) return

        myState = myState.copy(
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = ""
        )
        activeNotification?.expire()
        activeNotification = null
        pushPendingSuggestionToPanel()
    }

    private fun clearTrackedBaseline(currentHash: String, migrationFingerprint: String) {
        val hadTrackedState = myState.trackedBaselineStatements.isNotEmpty() ||
            myState.historyBaselineStatements.isNotEmpty() ||
            myState.pendingBaselineHash.isNotBlank() ||
            myState.pendingMigrationSql.isNotBlank() ||
            myState.lastNotificationHash.isNotBlank() ||
            activeNotification != null

        myState = myState.copy(
            trackedBaselineHash = currentHash,
            trackedBaselineStatements = emptyList(),
            historyBaselineHash = currentHash,
            historyBaselineStatements = emptyList(),
            pendingBaselineHash = "",
            pendingMigrationSql = "",
            lastNotificationHash = "",
            lastMigrationFingerprint = migrationFingerprint
        )

        if (!hadTrackedState) return

        activeNotification?.expire()
        activeNotification = null
    }

    private fun pushPendingSuggestionToPanel() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            MigrationVisualizerToolWindowFactory.getPanel(project)?.syncPendingMigrationSuggestion()
        }
    }

    private fun readSnapshot(): Snapshot =
        ReadAction.compute<Snapshot, Throwable> {
            val snapshot = snapshotService.getSnapshotUnderReadAction()
            Snapshot(
                migrations = snapshot.migrations,
                baselineStatements = snapshot.baselineStatements
            )
        }

    private fun hashMigrations(migrations: List<MigrationFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalized = migrations.joinToString(separator = "\n") {
            "${it.version}:${it.filePath}:${it.rawContent.trim()}"
        }
        return digest.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hashStatements(statements: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val normalized = statements.joinToString(separator = "\n;\n") { it.trim() }
        return digest.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {
        activeNotification?.expire()
        alarm.cancelAllRequests()
    }
}
