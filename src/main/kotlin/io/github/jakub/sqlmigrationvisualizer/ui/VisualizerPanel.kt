package io.github.jakub.sqlmigrationvisualizer.ui

import io.github.jakub.sqlmigrationvisualizer.model.PendingMigrationSuggestion
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.ValidationResult
import io.github.jakub.sqlmigrationvisualizer.parser.MigrationScanner
import io.github.jakub.sqlmigrationvisualizer.parser.SqlParser
import io.github.jakub.sqlmigrationvisualizer.services.ProjectSchemaSnapshotService
import io.github.jakub.sqlmigrationvisualizer.services.VisualizerPanelManager
import io.github.jakub.sqlmigrationvisualizer.settings.VisualizerSettings
import io.github.jakub.sqlmigrationvisualizer.validator.MigrationValidator
import io.github.jakub.sqlmigrationvisualizer.watcher.SchemaChangePromptService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Main panel that hosts the JCEF browser and manages the connection
 * between the IDE project and the web-based visualization.
 */
class VisualizerPanel(
    private val project: Project,
    parentDisposable: Disposable
) : Disposable {

    private data class RefreshPayload(
        val schemaVersions: List<SchemaVersion>,
        val validationResult: ValidationResult,
        val pendingSuggestion: PendingMigrationSuggestion
    )

    private val panel = JPanel(BorderLayout())
    private val browser: JBCefBrowser
    private val bridge: JcefBridge
    private val parser = SqlParser()
    private val validator = MigrationValidator()
    private val settings = VisualizerSettings.getInstance(project)
    private val snapshotService = project.getService(ProjectSchemaSnapshotService::class.java)
    private val schemaChangePromptService = project.getService(SchemaChangePromptService::class.java)
    private val panelManager = project.getService(VisualizerPanelManager::class.java)
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val refreshSequence = AtomicLong(0)
    @Volatile private var pendingSqRefresh = false
    @Volatile private var pendingSqmRefresh = false

    private var schemaVersions: List<SchemaVersion> = emptyList()
    private var validationResult: ValidationResult = ValidationResult(true, emptyList(), "Not yet validated")

    val component: JComponent get() = panel

    init {
        Disposer.register(parentDisposable, this)

        // Create JCEF browser with local HTML
        browser = JBCefBrowser()
        bridge = JcefBridge(project, browser)
        bridge.onRefreshRequested = { refreshData() }

        panel.add(browser.component, BorderLayout.CENTER)

        // Load the HTML UI
        loadWebUI()

        // Initial data scan
        refreshData()

        // Listen for IDE theme changes
        project.messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener { bridge.pushTheme(isIdeUsingDarkTheme()) }
        )
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach(::trackSchemaFileChange)
                    if (pendingSqRefresh || pendingSqmRefresh) {
                        scheduleAutoRefresh()
                    }
                }
            }
        )
    }

    private fun isIdeUsingDarkTheme(): Boolean {
        return EditorColorsManager.getInstance().isDarkEditor
    }

    /**
     * Load the bundled HTML file into the JCEF browser.
     */
    private fun loadWebUI() {
        val htmlContent = buildFullHtml()
        browser.loadHTML(htmlContent)
        bridge.injectBridge()
        bridge.pushTheme(isIdeUsingDarkTheme())
        bridge.pushSettings(settings.state)
    }

    private fun scheduleAutoRefresh() {
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({
            processAutoRefresh()
        }, 350)
    }

    private fun trackSchemaFileChange(event: VFileEvent) {
        val basePath = project.basePath ?: return
        if (!event.path.startsWith(basePath)) return
        val fileName = event.path.substringAfterLast('/')
        when {
            MigrationScanner.isMigrationFileName(fileName) -> pendingSqmRefresh = true
            MigrationScanner.isSchemaFileName(fileName) -> pendingSqRefresh = true
            event.path.endsWith(".sql") || event.path.endsWith(".ddl") -> {
                pendingSqmRefresh = true
                pendingSqRefresh = true
            }
        }
    }

    private fun processAutoRefresh() {
        val shouldRefreshSqm = pendingSqmRefresh
        val shouldRefreshSq = pendingSqRefresh
        pendingSqmRefresh = false
        pendingSqRefresh = false

        if (shouldRefreshSqm) {
            refreshData()
            return
        }

        if (!shouldRefreshSq) return

        val hasMigrations = ReadAction.compute<Boolean, Throwable> {
            snapshotService.getSnapshotUnderReadAction().migrations.isNotEmpty()
        }

        if (hasMigrations) {
            syncPendingMigrationSuggestion()
        } else {
            refreshData()
        }
    }

    /**
     * Re-scan project files and push updated data to the web UI.
     */
    fun refreshData() {
        val requestId = refreshSequence.incrementAndGet()
        val validateOnRefresh = settings.state.validateOnRefresh
        val previousValidation = validationResult

        ReadAction.nonBlocking<RefreshPayload> {
            val snapshot = snapshotService.getSnapshotUnderReadAction()
            val baselineStatements = if (snapshot.migrations.isEmpty()) {
                snapshot.baselineStatements
            } else {
                schemaChangePromptService.getTimelineBaselineStatements(hasMigrations = true)
            }
            val refreshedSchemaVersions = parser.buildSchemaTimeline(baselineStatements, snapshot.migrations)
            val refreshedValidation = if (validateOnRefresh) {
                validator.validate(snapshot.migrations, refreshedSchemaVersions)
            } else {
                previousValidation
            }

            RefreshPayload(
                schemaVersions = refreshedSchemaVersions,
                validationResult = refreshedValidation,
                pendingSuggestion = schemaChangePromptService.getPendingMigrationSuggestion()
            )
        }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { payload ->
                if (requestId != refreshSequence.get()) return@finishOnUiThread

                schemaVersions = payload.schemaVersions
                validationResult = payload.validationResult

                bridge.pushSchemaData(schemaVersions)
                bridge.pushValidationData(validationResult)
                bridge.pushSettings(settings.state)
                bridge.pushMigrationDirectory()
                bridge.pushPendingMigrationSuggestion(payload.pendingSuggestion)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Build the complete HTML page, inlining the stylesheet and every script into
     * the shell in `web/index.html`. JCEF's loadHTML gives the document no base
     * URL, so relative resource paths don't resolve — it all ships as one string.
     */
    private fun buildFullHtml(): String {
        val replacements = mapOf(
            "css" to loadResource("/web/styles.css"),
            "appJs" to loadResource("/web/app.js"),
            "sqlHighlightJs" to loadResource("/web/sql-highlight.js"),
            "timelineJs" to loadResource("/web/timeline.js"),
            "schemaDiffJs" to loadResource("/web/schema-diff.js"),
            "erDiagramJs" to loadResource("/web/er-diagram.js"),
            "searchJs" to loadResource("/web/search.js"),
            "exportJs" to loadResource("/web/export.js"),
            "createMigrationJs" to loadResource("/web/create-migration.js"),
            "headerIconBase64" to loadBinaryResourceBase64("/icons/panelIcon.png")
        )

        // Single pass, so injected CSS/JS is never itself rescanned for placeholders.
        return PLACEHOLDER_PATTERN.replace(loadResource("/web/index.html")) { match ->
            replacements[match.groupValues[1]] ?: match.value
        }
    }

    /**
     * Load a text resource file from the plugin's bundled resources.
     */
    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
            ?: "/* Resource not found: $path */"
    }

    private fun loadBinaryResourceBase64(path: String): String {
        return javaClass.getResourceAsStream(path)
            ?.use { Base64.getEncoder().encodeToString(it.readBytes()) }
            ?: ""
    }

    fun onSettingsChanged() {
        bridge.pushSettings(settings.state)
        bridge.pushMigrationDirectory()
        bridge.pushPendingMigrationSuggestion(schemaChangePromptService.getPendingMigrationSuggestion())
    }

    fun syncPendingMigrationSuggestion() {
        bridge.pushPendingMigrationSuggestion(schemaChangePromptService.getPendingMigrationSuggestion())
    }

    fun openPendingMigrationComposer() {
        bridge.openPendingMigrationComposer()
    }

    override fun dispose() {
        refreshAlarm.cancelAllRequests()
        panelManager.clearPanel(this)
        bridge.dispose()
    }

    private companion object {
        val PLACEHOLDER_PATTERN = Regex("""\{\{(\w+)}}""")
    }
}
