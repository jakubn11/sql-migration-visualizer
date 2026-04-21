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
     * Build the complete HTML page with all CSS and JS inlined,
     * since JCEF loadHTML doesn't support relative resource paths easily.
     */
    private fun buildFullHtml(): String {
        val css = loadResource("/web/styles.css")
        val appJs = loadResource("/web/app.js")
        val sqlHighlightJs = loadResource("/web/sql-highlight.js")
        val timelineJs = loadResource("/web/timeline.js")
        val schemaDiffJs = loadResource("/web/schema-diff.js")
        val erDiagramJs = loadResource("/web/er-diagram.js")
        val searchJs = loadResource("/web/search.js")
        val exportJs = loadResource("/web/export.js")
        val createMigrationJs = loadResource("/web/create-migration.js")
        val headerIconBase64 = loadBinaryResourceBase64("/icons/panelIcon.png")
        val headerIconHtml = """<img class="logo-icon-image" src="data:image/png;base64,$headerIconBase64" width="24" height="24" alt="" aria-hidden="true">"""

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SQL Migration Visualizer</title>
    <style>
$css
    </style>
</head>
<body>
    <div id="app">
        <header id="main-header">
            <div class="header-left">
                <div class="logo-area">
                    $headerIconHtml
                </div>
                <div class="header-copy">
                    <div class="header-title-row">
                        <h1>SQL Migration Visualizer</h1>
                    </div>
                    <p class="header-subtitle">Explore schema history, compare migrations, and catch migration drift before it lands.</p>
                </div>
            </div>
            <div class="header-right">
                <button class="btn btn-primary btn-sm" id="btn-create-migration" title="Create new migration file" onclick="window.AppActions && window.AppActions.handlePrimaryCreateAction()">
                    <svg id="btn-create-migration-icon" viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
                    <span id="btn-create-migration-label">Create Migration</span>
                </button>
                <div class="export-dropdown-container">
                    <button class="btn btn-ghost" id="btn-export" title="Export" onclick="document.getElementById('export-dropdown').classList.toggle('visible')">
                        <svg viewBox="0 0 24 24" width="16" height="16"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" fill="currentColor"/></svg>
                        <span>Export</span>
                    </button>
                    <div id="export-dropdown" class="export-dropdown">
                        <div class="export-dropdown-item" onclick="window.ExportModule && window.ExportModule.exportSchemaAsJson(); document.getElementById('export-dropdown').classList.remove('visible')">
                            <svg viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 9V3.5L18.5 9H13z" fill="currentColor"/></svg>
                            Schema as JSON
                        </div>
                        <div class="export-dropdown-item" onclick="window.ExportModule && window.ExportModule.exportSchemaAsSql(); document.getElementById('export-dropdown').classList.remove('visible')">
                            <svg viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 9V3.5L18.5 9H13z" fill="currentColor"/></svg>
                            Schema as SQL
                        </div>
                    </div>
                </div>
                <button class="btn btn-ghost" id="btn-refresh" title="Refresh migrations">
                    <svg viewBox="0 0 24 24" width="16" height="16"><path d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" fill="currentColor"/></svg>
                    <span>Refresh</span>
                </button>
            </div>
        </header>

        <section id="utility-bar" class="collapsible-section">
            <div class="section-collapsed-indicator">Search &amp; stats</div>
            <div class="utility-search-group">
                <div class="search-container">
                    <svg class="search-icon" viewBox="0 0 24 24" width="14" height="14"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" fill="currentColor"/></svg>
                    <input type="text" id="search-input" placeholder="Search tables, columns..." class="search-input">
                    <div id="search-results" class="search-results" style="display:none"></div>
                </div>
            </div>
            <div class="stats" id="stats-bar">
                <span class="stat-item" id="stat-versions"><span class="stat-num">0</span><span class="stat-label">Versions</span></span>
                <span class="stat-item" id="stat-tables"><span class="stat-num">0</span><span class="stat-label">Tables</span></span>
                <span class="stat-item" id="stat-migrations"><span class="stat-num">0</span><span class="stat-label">Migrations</span></span>
            </div>
        </section>

        <nav id="tab-bar">
            <button class="tab active" data-tab="timeline">
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M23 8c0 1.1-.9 2-2 2-.18 0-.35-.02-.51-.07l-3.56 3.55c.05.16.07.34.07.52 0 1.1-.9 2-2 2s-2-.9-2-2c0-.18.02-.36.07-.52l-2.55-2.55c-.16.05-.34.07-.52.07s-.36-.02-.52-.07l-4.55 4.56c.05.16.07.33.07.51 0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2c.18 0 .35.02.51.07l4.56-4.55C8.02 9.36 8 9.18 8 9c0-1.1.9-2 2-2s2 .9 2 2c0 .18-.02.36-.07.52l2.55 2.55c.16-.05.34-.07.52-.07s.36.02.52.07l3.55-3.56C19.02 8.35 19 8.18 19 8c0-1.1.9-2 2-2s2 .9 2 2z" fill="currentColor"/></svg>
                Timeline
            </button>
            <button class="tab" data-tab="diff">
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M9 14h6v-2H9v2zm-2 4h10v-2H7v2zm0-8h10V8H7v2zm-2 8V4h14v14H5zm0 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2z" fill="currentColor"/></svg>
                Schema Diff
            </button>
            <button class="tab" data-tab="er-diagram">
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M22 13h-8v-2h8v2zm0-6h-8v2h8V7zm-8 10h8v-2h-8v2zm-2-8v6c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V9c0-1.1.9-2 2-2h6c1.1 0 2 .9 2 2zm-1.5 0c0-.28-.22-.5-.5-.5H4c-.28 0-.5.22-.5.5v6c0 .28.22.5.5.5h6c.28 0 .5-.22.5-.5V9z" fill="currentColor"/></svg>
                ER Diagram
            </button>
            <button class="tab" data-tab="validation">
                <svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z" fill="currentColor"/></svg>
                Validation
                <span class="badge" id="validation-badge" style="display:none">0</span>
            </button>
        </nav>

        <section id="migration-suggestion-banner" class="collapsible-section" style="display:none">
            <div class="section-collapsed-indicator">Pending migration</div>
            <button type="button" class="section-inline-toggle" data-section-toggle="pendingBanner" aria-expanded="true" title="Collapse pending migration banner">
                <svg class="section-toggle-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
            </button>
            <div class="migration-suggestion-leading">
                <div class="migration-suggestion-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="18" height="18"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 10H8v-2h5v2zm0 4H8v-2h5v2zm0-8V3.5L18.5 9H13z" fill="currentColor"/></svg>
                </div>
                <div class="migration-suggestion-copy">
                    <div class="migration-suggestion-topline">
                        <div class="migration-suggestion-badge">Pending Migration</div>
                        <span class="migration-suggestion-kicker">Saved schema changes detected</span>
                    </div>
                    <strong id="migration-suggestion-title">Schema changes detected</strong>
                    <span id="migration-suggestion-text">Refresh to review the suggested migration.</span>
                    <div id="migration-suggestion-meta" class="migration-suggestion-meta" style="display:none"></div>
                </div>
            </div>
            <div class="migration-suggestion-actions">
                <span id="migration-suggestion-risk" class="risk-badge risk-badge-low" style="display:none">Low risk</span>
                <button class="btn btn-primary btn-sm" id="btn-create-pending-migration" onclick="window.AppActions && window.AppActions.quickCreatePendingMigration()">
                    Create Suggested Migration
                </button>
                <button class="btn btn-ghost btn-sm" id="btn-review-pending-migration" onclick="window.AppActions && window.AppActions.openMigrationComposer()">
                    Review Draft
                </button>
                <button class="btn btn-ghost btn-sm" id="btn-cancel-pending-migration" onclick="window.AppActions && window.AppActions.cancelPendingMigration()">
                    Cancel Pending
                </button>
            </div>
        </section>

        <main id="main-content">
            <section id="panel-timeline" class="panel active">
                <div id="timeline-container" class="collapsible-section">
                    <div class="section-collapsed-indicator">Timeline overview</div>
                    <div id="timeline-empty" class="empty-state">
                        <div class="empty-state-card empty-state-card-large">
                            <div class="empty-state-badge">Timeline Ready</div>
                            <svg viewBox="0 0 80 80" width="80" height="80" class="empty-icon">
                                <circle cx="40" cy="40" r="35" fill="none" stroke="currentColor" stroke-width="2" opacity="0.3"/>
                                <path d="M25 40h30M40 25v30" stroke="currentColor" stroke-width="2" opacity="0.3" stroke-linecap="round"/>
                            </svg>
                            <h3>No migrations found yet</h3>
                            <p>Add versioned migration files to your project to explore schema history, compare versions, and validate changes in one place.</p>
                            <div class="empty-state-actions">
                                <button class="btn btn-primary" onclick="window.__bridge && window.__bridge.requestRefresh()">Scan Project</button>
                                <button class="btn btn-ghost" onclick="window.AppActions && window.AppActions.openMigrationComposer()">
                                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
                                    Create Migration
                                </button>
                            </div>
                        </div>
                    </div>
                    <div id="timeline-hover-preview" style="display:none"></div>
                    <div id="timeline-track" style="display:none"></div>
                </div>
                <div id="schema-detail" style="display:none">
                    <div id="schema-detail-header">
                        <div class="schema-detail-heading">
                            <h2 id="schema-detail-title">Version 0</h2>
                            <p id="schema-detail-subtitle">Select a migration to inspect its tables and schema details.</p>
                        </div>
                        <div class="schema-detail-toolbar">
                            <div class="segmented-control" aria-label="Timeline table filter">
                                <button class="segmented-control-btn active" id="btn-timeline-filter-all" type="button">All tables</button>
                                <button class="segmented-control-btn" id="btn-timeline-filter-changed" type="button">Changed only</button>
                            </div>
                            <div id="schema-detail-actions">
                                <button class="btn btn-ghost btn-sm" id="btn-goto-source" title="Edit migration file">
                                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm14.71-9.04c.39-.39.39-1.02 0-1.41L15.2 4.29a.9959.9959 0 0 0-1.41 0l-1.96 1.96 3.75 3.75 2.13-1.79z" fill="currentColor"/></svg>
                                    Edit migration
                                </button>
                                <button class="btn btn-ghost btn-sm" id="btn-delete-migration" title="Delete migration file" style="display:none">
                                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M6 7h12l-1 14H7L6 7zm3-4h6l1 2h4v2H4V5h4l1-2z" fill="currentColor"/></svg>
                                    Delete migration
                                </button>
                            </div>
                            <button type="button" class="section-header-toggle" data-section-toggle="schemaDetail" aria-expanded="true" title="Collapse selected migration">
                                <svg class="section-toggle-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
                            </button>
                        </div>
                    </div>
                    <div class="schema-legend" aria-label="Schema legend">
                        <span class="schema-legend-title">Legend</span>
                        <span class="schema-legend-item"><span class="col-pk">PK</span><span class="schema-legend-text">Primary key</span></span>
                        <span class="schema-legend-item"><span class="col-fk">FK</span><span class="schema-legend-text">Foreign key</span></span>
                        <span class="schema-legend-item"><span class="col-nullable">NOT NULL</span><span class="schema-legend-text">Required column</span></span>
                        <span class="schema-legend-item"><span class="legend-dot added"></span><span class="schema-legend-text">Added</span></span>
                        <span class="schema-legend-item"><span class="legend-dot modified"></span><span class="schema-legend-text">Modified</span></span>
                        <span class="schema-legend-item"><span class="legend-dot removed"></span><span class="schema-legend-text">Removed</span></span>
                        <span class="schema-legend-note"><code>col</code>/<code>cols</code> = columns</span>
                    </div>
                    <div id="schema-detail-body">
                        <div id="table-history-panel" style="display:none"></div>
                        <div id="schema-tables-grid"></div>
                    </div>
                    <div id="changes-summary" class="collapsible-section" style="display:none">
                        <div class="section-collapsed-indicator">Change summary</div>
                        <button type="button" class="section-inline-toggle" data-section-toggle="changesSummary" aria-expanded="true" title="Collapse change summary">
                            <svg class="section-toggle-icon" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
                        </button>
                        <h3>Changes in this version</h3>
                        <div id="changes-list"></div>
                    </div>
                    <div id="raw-sql-section" style="display:none">
                        <div class="raw-sql-toggle" onclick="document.getElementById('raw-sql-body').classList.toggle('expanded'); this.querySelector('.toggle-arrow').classList.toggle('rotated')">
                            <svg class="toggle-arrow" viewBox="0 0 24 24" width="12" height="12" style="transition: transform 0.2s"><path d="M8 5v14l11-7z" fill="currentColor"/></svg>
                            <svg viewBox="0 0 24 24" width="12" height="12" style="opacity:0.5"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 9V3.5L18.5 9H13z" fill="currentColor"/></svg>
                            Raw SQL
                        </div>
                        <div id="raw-sql-body" class="raw-sql-content"></div>
                    </div>
                </div>
            </section>

            <section id="panel-diff" class="panel">
                <div class="diff-controls">
                    <div class="diff-selector">
                        <label>From version:</label>
                        <div class="version-dropdown" data-select-id="diff-from">
                            <button type="button" class="version-dropdown-trigger" aria-haspopup="listbox" aria-expanded="false">
                                <span class="version-dropdown-label">Select version</span>
                                <svg class="version-dropdown-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
                            </button>
                            <div class="version-dropdown-menu" role="listbox"></div>
                        </div>
                        <select id="diff-from" class="version-select-native" tabindex="-1" aria-hidden="true"></select>
                    </div>
                    <svg class="diff-arrow" viewBox="0 0 24 24" width="24" height="24"><path d="M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8-8-8z" fill="currentColor"/></svg>
                    <div class="diff-selector">
                        <label>To version:</label>
                        <div class="version-dropdown" data-select-id="diff-to">
                            <button type="button" class="version-dropdown-trigger" aria-haspopup="listbox" aria-expanded="false">
                                <span class="version-dropdown-label">Select version</span>
                                <svg class="version-dropdown-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
                            </button>
                            <div class="version-dropdown-menu" role="listbox"></div>
                        </div>
                        <select id="diff-to" class="version-select-native" tabindex="-1" aria-hidden="true"></select>
                    </div>
                    <button class="btn btn-primary btn-sm" id="btn-generate-migration" style="display:none">
                        <svg viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 9h-2v2H9v-2H7v-2h2V7h2v2h2v2zm-2-8l5.5 5.5H13V3z" fill="currentColor"/></svg>
                        Generate migration
                    </button>
                </div>
                <div id="diff-summary" aria-live="polite"></div>
                <div id="diff-content"></div>
            </section>

            <section id="panel-er-diagram" class="panel">
                <div class="er-controls">
                    <div class="diff-selector">
                        <label>Version:</label>
                        <div class="version-dropdown" data-select-id="er-version">
                            <button type="button" class="version-dropdown-trigger" aria-haspopup="listbox" aria-expanded="false">
                                <span class="version-dropdown-label">Select version</span>
                                <svg class="version-dropdown-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M7 10l5 5 5-5z" fill="currentColor"/></svg>
                            </button>
                            <div class="version-dropdown-menu" role="listbox"></div>
                        </div>
                        <select id="er-version" class="version-select-native" tabindex="-1" aria-hidden="true"></select>
                    </div>
                    <div class="er-zoom-controls" aria-label="ER diagram zoom controls">
                        <button class="btn btn-ghost btn-sm" id="btn-er-zoom-out" title="Zoom out">
                            <svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13H5v-2h14v2z" fill="currentColor"/></svg>
                        </button>
                        <span class="er-zoom-level" id="er-zoom-level">100%</span>
                        <button class="btn btn-ghost btn-sm" id="btn-er-zoom-in" title="Zoom in">
                            <svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
                        </button>
                    </div>
                    <button class="btn btn-ghost btn-sm" id="btn-er-reset" title="Reset view">
                        <svg viewBox="0 0 24 24" width="14" height="14"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z" fill="currentColor"/></svg>
                        Fit to View
                    </button>
                    <button class="btn btn-ghost btn-sm" onclick="window.ExportModule && window.ExportModule.exportErAsPng()" title="Export as PNG">
                        <svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" fill="currentColor"/></svg>
                        Export PNG
                    </button>
                    <div class="er-legend" aria-label="ER diagram legend">
                        <span class="schema-legend-item"><span class="col-pk">PK</span><span class="schema-legend-text">Primary key</span></span>
                        <span class="schema-legend-item"><span class="col-fk">FK</span><span class="schema-legend-text">Foreign key</span></span>
                    </div>
                </div>
                <canvas id="er-canvas"></canvas>
            </section>

            <section id="panel-validation" class="panel">
                <div id="validation-summary"></div>
                <div id="validation-issues"></div>
            </section>
        </main>
    </div>

    <!-- Migration Generation Modal -->
    <div id="migration-modal" class="modal-overlay" style="display:none" onclick="if(event.target===this)this.style.display='none'">
        <div class="modal-content">
            <div class="modal-header">
                <h3>Generated Migration</h3>
                <button class="btn btn-ghost btn-sm" onclick="document.getElementById('migration-modal').style.display='none'">
                    <svg viewBox="0 0 24 24" width="16" height="16"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" fill="currentColor"/></svg>
                </button>
            </div>
            <div class="modal-body">
                <pre class="sql-block" id="migration-code"></pre>
            </div>
            <div class="modal-actions">
                <button class="btn btn-ghost btn-sm" onclick="window.AppUi && window.AppUi.copyText(document.getElementById('migration-modal')._rawSql||'', 'Migration SQL copied.')">
                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" fill="currentColor"/></svg>
                    Copy
                </button>
                <button class="btn btn-ghost btn-sm" onclick="window.AppActions && window.AppActions.openGeneratedMigrationAsDraft()">
                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm14.71-9.04c.39-.39.39-1.02 0-1.41L15.2 4.29a.9959.9959 0 0 0-1.41 0l-1.96 1.96 3.75 3.75 2.13-1.79z" fill="currentColor"/></svg>
                    Edit as Draft
                </button>
                <button class="btn btn-primary btn-sm" onclick="window.__bridge && window.__bridge.saveFile(JSON.stringify({fileName:(window.AppHelpers && window.AppHelpers.getSuggestedMigrationFileName ? window.AppHelpers.getSuggestedMigrationFileName({sql:document.getElementById('migration-modal')._rawSql||''}) : 'migration.sql'),content:document.getElementById('migration-modal')._rawSql||'',encoding:'utf8'}))">
                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z" fill="currentColor"/></svg>
                    Save As...
                </button>
            </div>
        </div>
    </div>

    <!-- Create Migration Modal -->
    <div id="create-migration-modal" class="modal-overlay" style="display:none" onclick="if(event.target===this) window.CreateMigrationModule && window.CreateMigrationModule.closeModal()">
        <div class="modal-content" style="max-width: 520px">
            <div class="modal-header">
                <h3>Create New Migration</h3>
                <button class="btn btn-ghost btn-sm" onclick="window.CreateMigrationModule && window.CreateMigrationModule.closeModal()">
                    <svg viewBox="0 0 24 24" width="16" height="16"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" fill="currentColor"/></svg>
                </button>
            </div>
            <div class="modal-body">
                <div id="create-mig-error" class="form-error" style="display:none"></div>
                <div id="create-mig-context" class="composer-context-card" style="display:none">
                    <div class="composer-context-header">
                        <div>
                            <div class="composer-context-eyebrow" id="create-mig-source-badge">Migration Draft</div>
                            <strong id="create-mig-summary-title">Create migration</strong>
                            <div id="create-mig-summary-text" class="composer-context-summary"></div>
                        </div>
                        <div id="create-mig-risk-badge"></div>
                    </div>
                    <div class="composer-context-grid">
                        <div class="composer-context-pane">
                            <div class="composer-context-label">Planned file</div>
                            <div id="create-mig-file-preview" class="composer-context-preview">1.sql</div>
                            <div id="create-mig-path-preview" class="composer-context-helper">Choose a directory to preview the full path.</div>
                        </div>
                        <div class="composer-context-pane">
                            <div class="composer-context-label">Why review it</div>
                            <div id="create-mig-highlights" class="composer-chip-list"></div>
                        </div>
                    </div>
                    <div id="create-mig-risk-list" class="composer-risk-list"></div>
                </div>
                <div class="form-row">
                    <div class="form-group" style="flex: 0 0 100px">
                        <label class="form-label">Version</label>
                        <div class="form-input-group version-input-group">
                            <input type="number" id="create-mig-version" class="form-input version-number-input" min="1" value="1">
                            <div class="version-stepper" aria-label="Version stepper">
                                <button class="btn btn-ghost btn-sm version-step-btn" type="button" title="Decrease version" onclick="window.CreateMigrationModule && window.CreateMigrationModule.stepVersion(-1)">
                                    <svg viewBox="0 0 24 24" width="12" height="12"><path d="M19 13H5v-2h14v2z" fill="currentColor"/></svg>
                                </button>
                                <button class="btn btn-ghost btn-sm version-step-btn" type="button" title="Increase version" onclick="window.CreateMigrationModule && window.CreateMigrationModule.stepVersion(1)">
                                    <svg viewBox="0 0 24 24" width="12" height="12"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
                                </button>
                            </div>
                        </div>
                    </div>
                    <div class="form-group" style="flex: 1">
                        <label class="form-label">Directory</label>
                        <input type="text" id="create-mig-directory" class="form-input form-input-clickable" placeholder="Click to choose migration directory..." readonly onclick="window.CreateMigrationModule && window.CreateMigrationModule.browseDirectory()" title="Click to choose directory">
                    </div>
                </div>
                <div class="form-group" id="create-mig-name-group">
                    <label class="form-label">Name</label>
                    <input type="text" id="create-mig-name" class="form-input" placeholder="users_changes">
                </div>
                <div class="form-group">
                    <label class="form-label">SQL Statements</label>
                    <div class="sql-editor" data-placeholder="ALTER TABLE users ADD COLUMN email TEXT NOT NULL;&#10;&#10;CREATE TABLE posts (&#10;  id INTEGER PRIMARY KEY,&#10;  title TEXT NOT NULL&#10;);">
                        <pre id="create-mig-sql-highlight" class="sql-editor-highlight" aria-hidden="true"></pre>
                        <textarea id="create-mig-sql" class="form-textarea sql-editor-input" placeholder="ALTER TABLE users ADD COLUMN email TEXT NOT NULL;&#10;&#10;CREATE TABLE posts (&#10;  id INTEGER PRIMARY KEY,&#10;  title TEXT NOT NULL&#10;);" rows="10" spellcheck="false"></textarea>
                        <div id="create-mig-sql-suggestions" class="sql-editor-suggestions" style="display:none"></div>
                    </div>
                </div>
            </div>
            <div class="modal-actions">
                <button class="btn btn-ghost btn-sm" onclick="window.CreateMigrationModule && window.CreateMigrationModule.closeModal()">Cancel</button>
                <button class="btn btn-primary btn-sm" id="create-mig-submit" onclick="window.CreateMigrationModule && window.CreateMigrationModule.submit()">
                    <svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>
                    Create File
                </button>
            </div>
        </div>
    </div>

    <div id="confirm-modal" class="modal-overlay" style="display:none" onclick="if(event.target===this) window.AppUi && window.AppUi.closeConfirm()">
        <div class="modal-content confirm-modal-content">
            <div class="modal-header">
                <h3 id="confirm-modal-title">Confirm Action</h3>
                <button class="btn btn-ghost btn-sm" onclick="window.AppUi && window.AppUi.closeConfirm()">
                    <svg viewBox="0 0 24 24" width="16" height="16"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" fill="currentColor"/></svg>
                </button>
            </div>
            <div class="modal-body confirm-modal-body">
                <div class="confirm-modal-icon" id="confirm-modal-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="20" height="20"><path d="M12 2 1 21h22L12 2zm1 16h-2v-2h2v2zm0-4h-2v-4h2v4z" fill="currentColor"/></svg>
                </div>
                <div class="confirm-modal-copy">
                    <p id="confirm-modal-message">Are you sure?</p>
                </div>
            </div>
            <div class="modal-actions">
                <button class="btn btn-ghost btn-sm" onclick="window.AppUi && window.AppUi.closeConfirm()">Cancel</button>
                <button class="btn btn-danger btn-sm" id="confirm-modal-submit" onclick="window.AppUi && window.AppUi.submitConfirm()">Delete</button>
            </div>
        </div>
    </div>

    <div id="toast-container" aria-live="polite" aria-atomic="true"></div>

    <script>
$appJs
    </script>
    <script>
$sqlHighlightJs
    </script>
    <script>
$timelineJs
    </script>
    <script>
$schemaDiffJs
    </script>
    <script>
$erDiagramJs
    </script>
    <script>
$searchJs
    </script>
    <script>
$exportJs
    </script>
    <script>
$createMigrationJs
    </script>
</body>
</html>
        """.trimIndent()
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
}
