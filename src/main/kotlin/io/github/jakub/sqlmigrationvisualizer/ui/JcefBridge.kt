package io.github.jakub.sqlmigrationvisualizer.ui

import io.github.jakub.sqlmigrationvisualizer.generator.MigrationGenerator
import io.github.jakub.sqlmigrationvisualizer.generator.SqlDialect
import io.github.jakub.sqlmigrationvisualizer.model.PendingMigrationSuggestion
import io.github.jakub.sqlmigrationvisualizer.model.SchemaVersion
import io.github.jakub.sqlmigrationvisualizer.model.ValidationResult
import io.github.jakub.sqlmigrationvisualizer.parser.MigrationScanner
import io.github.jakub.sqlmigrationvisualizer.services.ProjectSchemaSnapshotService
import io.github.jakub.sqlmigrationvisualizer.settings.VisualizerSettings
import io.github.jakub.sqlmigrationvisualizer.util.MigrationFileNaming
import io.github.jakub.sqlmigrationvisualizer.util.MigrationDirectoryDetector
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.io.File

/**
 * Manages bidirectional communication between Kotlin and the JCEF-rendered web UI.
 *
 * Kotlin → JS: Pushes schema data as JSON via executeJavaScript()
 * JS → Kotlin: Receives commands via JBCefJSQuery callbacks (e.g., "open file", "validate")
 */
class JcefBridge(
    private val project: Project,
    private val browser: JBCefBrowserBase
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    // Query objects for JS→Kotlin communication
    private val openFileQuery = JBCefJSQuery.create(browser)
    private val requestRefreshQuery = JBCefJSQuery.create(browser)
    private val generateMigrationQuery = JBCefJSQuery.create(browser)
    private val saveMigrationQuery = JBCefJSQuery.create(browser)
    private val saveFileQuery = JBCefJSQuery.create(browser)
    private val createMigrationQuery = JBCefJSQuery.create(browser)
    private val browseDirectoryQuery = JBCefJSQuery.create(browser)
    private val deleteMigrationQuery = JBCefJSQuery.create(browser)
    private val saveErLayoutQuery = JBCefJSQuery.create(browser)
    private val dismissPendingMigrationQuery = JBCefJSQuery.create(browser)
    private val openRelatedSchemaSourceQuery = JBCefJSQuery.create(browser)
    private val saveDiffSelectionQuery = JBCefJSQuery.create(browser)

    private val migrationGenerator = MigrationGenerator()
    private val settings = VisualizerSettings.getInstance(project)
    private val schemaChangePromptService = project.getService(io.github.jakub.sqlmigrationvisualizer.watcher.SchemaChangePromptService::class.java)
    private val snapshotService = project.getService(ProjectSchemaSnapshotService::class.java)

    var onRefreshRequested: (() -> Unit)? = null
    var schemaVersions: List<SchemaVersion> = emptyList()
    private var validationResult: ValidationResult = ValidationResult(true, emptyList(), "Not yet validated")
    private var pendingSuggestion: PendingMigrationSuggestion = PendingMigrationSuggestion()
    private var settingsState: VisualizerSettings.State = VisualizerSettings.State()

    init {
        setupQueryHandlers()
    }

    /**
     * Set up JS→Kotlin callback handlers.
     */
    private fun setupQueryHandlers() {
        // Handle "open file" requests from JavaScript
        openFileQuery.addHandler { filePath ->
            openFileInEditor(filePath)
            JBCefJSQuery.Response("ok")
        }

        // Handle refresh requests from JavaScript
        requestRefreshQuery.addHandler { _ ->
            onRefreshRequested?.invoke()
            JBCefJSQuery.Response("ok")
        }

        // Handle migration generation requests
        generateMigrationQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val fromVersion = params["fromVersion"]!!.jsonPrimitive.int
                val toVersion = params["toVersion"]!!.jsonPrimitive.int

                val fromSchema = schemaVersions.find { it.version == fromVersion }
                val toSchema = schemaVersions.find { it.version == toVersion }

                if (fromSchema != null && toSchema != null) {
                    val sql = migrationGenerator.generateMigration(fromSchema, toSchema, selectedDialect())
                    JBCefJSQuery.Response(sql)
                } else {
                    JBCefJSQuery.Response(null, 0, "Schema version not found")
                }
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        // Handle save migration file requests
        saveMigrationQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val filePath = params["filePath"]!!.jsonPrimitive.content
                val content = params["content"]!!.jsonPrimitive.content
                val openAfterSave = params["openAfterSave"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        val virtualFile = writeUtf8File(filePath, content)
                        if (openAfterSave) {
                            FileEditorManager.getInstance(project).openFile(virtualFile, true)
                        }
                    }
                    onRefreshRequested?.invoke()
                }
                JBCefJSQuery.Response(filePath)
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        // Handle generic file save requests (for export)
        saveFileQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val fileName = params["fileName"]!!.jsonPrimitive.content
                val content = params["content"]!!.jsonPrimitive.content
                val encoding = params["encoding"]?.jsonPrimitive?.content ?: "utf8"

                ApplicationManager.getApplication().invokeLater {
                    val frame = java.awt.Frame.getFrames().firstOrNull()
                    val dialog = java.awt.FileDialog(frame, "Save As", java.awt.FileDialog.SAVE)
                    dialog.file = fileName
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        val dest = File(dir, file)
                        WriteAction.run<Throwable> {
                            if (encoding == "base64") {
                                writeBinaryFile(dest.absolutePath, java.util.Base64.getDecoder().decode(content))
                            } else {
                                writeUtf8File(dest.absolutePath, content)
                            }
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        // Handle create migration file requests
        createMigrationQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val version = params["version"]!!.jsonPrimitive.int
                val directory = params["directory"]!!.jsonPrimitive.content
                val content = params["content"]!!.jsonPrimitive.content
                val name = params["name"]?.jsonPrimitive?.content
                val extension = params["extension"]?.jsonPrimitive?.content
                    ?.trim()
                    ?.removePrefix(".")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("""[a-z0-9]+""")) }
                    ?: detectPreferredMigrationExtension()

                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        val fileName = MigrationFileNaming.buildFileName(
                            pattern = settings.state.migrationFileNamePattern,
                            version = version,
                            name = name,
                            extension = extension
                        )
                        val filePath = File(directory, fileName).absolutePath
                        val virtualFile = writeUtf8File(filePath, content)
                        settings.rememberMigrationDirectory(directory)
                        settingsState = settings.state
                        if (settings.state.autoOpenCreatedMigration) {
                            FileEditorManager.getInstance(project).openFile(virtualFile, true)
                        }
                    }
                    onRefreshRequested?.invoke()
                }
                val fileName = MigrationFileNaming.buildFileName(
                    pattern = settings.state.migrationFileNamePattern,
                    version = version,
                    name = name,
                    extension = extension
                )
                JBCefJSQuery.Response(File(directory, fileName).absolutePath)
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        // Handle browse directory requests
        browseDirectoryQuery.addHandler { currentDir ->
            ApplicationManager.getApplication().invokeLater {
                val frame = java.awt.Frame.getFrames().firstOrNull()
                val previous = System.getProperty("apple.awt.fileDialogForDirectories")
                try {
                    System.setProperty("apple.awt.fileDialogForDirectories", "true")
                    val dialog = java.awt.FileDialog(frame, "Select Migration Directory", java.awt.FileDialog.LOAD)
                    if (currentDir.isNotEmpty()) {
                        dialog.directory = currentDir
                    } else {
                        dialog.directory = project.basePath
                    }
                    dialog.isVisible = true
                    val directory = dialog.directory
                    val file = dialog.file
                    if (directory != null) {
                        val selectedDir = if (file != null) {
                            File(directory, file).absolutePath
                        } else {
                            File(directory).absolutePath
                        }
                        executeJavaScript(
                            "window.__onDirectorySelected && window.__onDirectorySelected(${selectedDir.asJsStringLiteral()});",
                            "bridge://dir"
                        )
                    }
                } finally {
                    if (previous == null) {
                        System.clearProperty("apple.awt.fileDialogForDirectories")
                    } else {
                        System.setProperty("apple.awt.fileDialogForDirectories", previous)
                    }
                }
            }
            JBCefJSQuery.Response("ok")
        }

        // Handle delete migration file requests
        deleteMigrationQuery.addHandler { filePath ->
            try {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        WriteAction.run<Throwable> {
                            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                                ?: throw IllegalStateException("Migration file not found: $filePath")
                            FileEditorManager.getInstance(project).closeFile(virtualFile)
                            virtualFile.delete(this@JcefBridge)
                            virtualFile.parent?.refresh(false, true)
                        }
                        onRefreshRequested?.invoke()
                    } catch (e: Exception) {
                        executeJavaScript(
                            "window.alert(${("Failed to delete migration: ${e.message ?: "Unknown error"}").asJsStringLiteral()});",
                            "bridge://delete-error"
                        )
                    }
                }
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        saveErLayoutQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val version = params["version"]!!.jsonPrimitive.content
                val positions = params["positions"]!!.jsonObject.mapValues { (_, value) ->
                    val point = value.jsonObject
                    VisualizerSettings.ErTablePosition(
                        x = point["x"]!!.jsonPrimitive.content.toDouble(),
                        y = point["y"]!!.jsonPrimitive.content.toDouble()
                    )
                }
                settings.saveErLayout(version, positions)
                settingsState = settings.state
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        dismissPendingMigrationQuery.addHandler { _ ->
            try {
                schemaChangePromptService.dismissPendingSuggestion()
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        openRelatedSchemaSourceQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val tableName = params["tableName"]!!.jsonPrimitive.content
                val columnName = params["columnName"]?.jsonPrimitive?.content
                val filePath = snapshotService.findRelatedSchemaFile(tableName, columnName)
                    ?: return@addHandler JBCefJSQuery.Response(null, 0, "No related schema file found for $tableName")
                openFileInEditor(filePath)
                JBCefJSQuery.Response(filePath)
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }

        saveDiffSelectionQuery.addHandler { jsonStr ->
            try {
                val params = Json.parseToJsonElement(jsonStr).jsonObject
                val fromVersion = params["fromVersion"]!!.jsonPrimitive.int
                val toVersion = params["toVersion"]!!.jsonPrimitive.int
                settings.saveDiffSelection(fromVersion, toVersion)
                settingsState = settings.state
                JBCefJSQuery.Response("ok")
            } catch (e: Exception) {
                JBCefJSQuery.Response(null, 0, "Error: ${e.message}")
            }
        }
    }

    /**
     * Wait for the page to load, then inject the bridge functions
     * and push the initial data.
     */
    fun injectBridge() {
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    // Inject the bridge functions
                    val bridgeJs = buildBridgeFunctions()
                    cefBrowser?.executeJavaScript(bridgeJs, "bridge://init", 0)

                    pushSchemaData(this@JcefBridge.schemaVersions)
                    pushValidationData(this@JcefBridge.validationResult)
                    pushSettings(this@JcefBridge.settingsState)
                    pushPendingMigrationSuggestion(this@JcefBridge.pendingSuggestion)
                    pushMigrationDirectory()
                }
            }
        }, browser.cefBrowser)
    }

    /**
     * Creates JavaScript bridge functions that JS code can call to communicate
     * with Kotlin.
     */
    private fun buildBridgeFunctions(): String {
        val openFileJs = openFileQuery.inject("filePath")
        val refreshJs = requestRefreshQuery.inject("''")
        val generateMigrationJs = generateMigrationQuery.inject("json",
            "function(response) { window.__onMigrationGenerated && window.__onMigrationGenerated(response); }",
            "function(errCode, errMsg) { console.error('[Bridge] Generate migration error:', errMsg); }"
        )
        val saveMigrationJs = saveMigrationQuery.inject("json",
            "function(response) { console.log('[Bridge] Migration saved'); window.__onMigrationSaved && window.__onMigrationSaved(response); }",
            "function(errCode, errMsg) { console.error('[Bridge] Save migration error:', errMsg); window.__onMigrationSaveError && window.__onMigrationSaveError(errMsg); }"
        )
        val saveFileJs = saveFileQuery.inject("json",
            "function(response) { console.log('[Bridge] File saved'); }",
            "function(errCode, errMsg) { console.error('[Bridge] Save file error:', errMsg); }"
        )
        val createMigrationJs = createMigrationQuery.inject("json",
            "function(response) { window.__onMigrationCreated && window.__onMigrationCreated(response); }",
            "function(errCode, errMsg) { console.error('[Bridge] Create migration error:', errMsg); window.__onCreateMigrationError && window.__onCreateMigrationError(errMsg); }"
        )
        val browseDirectoryJs = browseDirectoryQuery.inject("currentDir",
            "function(response) { console.log('[Bridge] Directory browsed'); }",
            "function(errCode, errMsg) { console.error('[Bridge] Browse directory error:', errMsg); }"
        )
        val deleteMigrationJs = deleteMigrationQuery.inject("filePath",
            "function(response) { console.log('[Bridge] Migration deleted'); window.__onMigrationDeleted && window.__onMigrationDeleted(filePath); }",
            "function(errCode, errMsg) { console.error('[Bridge] Delete migration error:', errMsg); }"
        )
        val saveErLayoutJs = saveErLayoutQuery.inject("json",
            "function(response) { console.log('[Bridge] ER layout saved'); }",
            "function(errCode, errMsg) { console.error('[Bridge] Save ER layout error:', errMsg); }"
        )
        val dismissPendingMigrationJs = dismissPendingMigrationQuery.inject("''",
            "function(response) { console.log('[Bridge] Pending migration dismissed'); window.__onPendingMigrationDismissed && window.__onPendingMigrationDismissed(); }",
            "function(errCode, errMsg) { console.error('[Bridge] Dismiss pending migration error:', errMsg); }"
        )
        val openRelatedSchemaSourceJs = openRelatedSchemaSourceQuery.inject("json",
            "function(response) { console.log('[Bridge] Related schema source opened'); }",
            "function(errCode, errMsg) { console.error('[Bridge] Open related schema source error:', errMsg); window.__onRelatedSchemaOpenFailed && window.__onRelatedSchemaOpenFailed(errMsg); }"
        )
        val saveDiffSelectionJs = saveDiffSelectionQuery.inject("json",
            "function(response) { console.log('[Bridge] Diff selection saved'); }",
            "function(errCode, errMsg) { console.error('[Bridge] Save diff selection error:', errMsg); }"
        )

        return """
            window.__bridge = {
                openFile: function(filePath) {
                    $openFileJs
                },
                requestRefresh: function() {
                    $refreshJs
                },
                generateMigration: function(json) {
                    $generateMigrationJs
                },
                saveMigration: function(json) {
                    $saveMigrationJs
                },
                saveFile: function(json) {
                    $saveFileJs
                },
                createMigration: function(json) {
                    $createMigrationJs
                },
                browseDirectory: function(currentDir) {
                    $browseDirectoryJs
                },
                deleteMigration: function(filePath) {
                    $deleteMigrationJs
                },
                saveErLayout: function(json) {
                    $saveErLayoutJs
                },
                dismissPendingMigration: function() {
                    $dismissPendingMigrationJs
                },
                openRelatedSchemaSource: function(json) {
                    $openRelatedSchemaSourceJs
                },
                saveDiffSelection: function(json) {
                    $saveDiffSelectionJs
                }
            };
            console.log('[Bridge] Kotlin bridge injected');
        """.trimIndent()
    }

    /**
     * Push schema version data to the JavaScript layer.
     */
    fun pushSchemaData(schemaVersions: List<SchemaVersion>) {
        this.schemaVersions = schemaVersions
        pushJsonPayload("window.__onSchemaData", json.encodeToString(schemaVersions), "bridge://data")
    }

    fun pushPendingMigrationSuggestion(suggestion: PendingMigrationSuggestion) {
        pendingSuggestion = suggestion
        pushJsonPayload("window.__onPendingMigrationData", json.encodeToString(suggestion), "bridge://pending-migration")
    }

    /**
     * Push validation results to the JavaScript layer.
     */
    fun pushValidationData(validationResult: ValidationResult) {
        this.validationResult = validationResult
        pushJsonPayload("window.__onValidationData", json.encodeToString(validationResult), "bridge://validation")
    }

    /**
     * Open a file in the IDE editor by its path.
     */
    private fun openFileInEditor(filePath: String) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath) ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    /**
     * Push settings to the JavaScript layer.
     */
    fun pushSettings(settings: VisualizerSettings.State) {
        settingsState = settings
        pushJsonPayload("window.__onSettingsChanged", json.encodeToString(settings), "bridge://settings")
    }

    /**
     * Push theme (dark/light) to the JavaScript layer.
     */
    fun pushTheme(isDark: Boolean) {
        val theme = if (isDark) "dark" else "light"
        executeJavaScript(
            "document.documentElement.setAttribute('data-theme', '$theme'); " +
            "if (window.ERDiagramModule && window.ERDiagramModule.getThemeColors) { " +
            "  window.ERDiagramModule.colors = window.ERDiagramModule.getThemeColors(); " +
            "}",
            "bridge://theme"
        )
    }

    /**
     * Push the default migration directory to JavaScript.
     */
    fun pushMigrationDirectory() {
        val dir = detectMigrationDirectory()
        executeJavaScript(
            "window.__defaultMigrationDir = ${dir.asJsStringLiteral()};",
            "bridge://dir"
        )
    }

    fun openPendingMigrationComposer() {
        executeJavaScript(
            "window.AppActions && window.AppActions.openMigrationComposer();",
            "bridge://pending-open"
        )
    }

    private fun detectMigrationDirectory(): String {
        return MigrationDirectoryDetector.detect(
            basePath = project.basePath,
            preferredDirectory = settings.state.defaultMigrationDirectory,
            existingMigrationPaths = schemaVersions.mapNotNull { schemaVersion ->
                schemaVersion.migrationFile?.filePath
            },
            configuredDirectories = settings.configuredMigrationDirectories()
        )
    }

    private fun detectPreferredMigrationExtension(): String =
        MigrationScanner.detectPreferredMigrationExtension(
            schemaVersions.mapNotNull { schemaVersion -> schemaVersion.migrationFile?.filePath }
        )

    private fun selectedDialect(): SqlDialect =
        SqlDialect.fromId(settings.state.preferredSqlDialect)

    private fun pushJsonPayload(callbackName: String, payload: String, source: String) {
        executeJavaScript(
            "$callbackName && $callbackName(${payload.asJsStringLiteral()});",
            source
        )
    }

    private fun executeJavaScript(script: String, source: String) {
        browser.cefBrowser.executeJavaScript(script, source, 0)
    }

    private fun writeUtf8File(filePath: String, content: String): VirtualFile {
        val ioFile = File(filePath)
        val parentPath = ioFile.parent ?: throw IllegalArgumentException("Parent directory not found for $filePath")
        val parentDir = VfsUtil.createDirectoryIfMissing(parentPath)
            ?: throw IllegalStateException("Unable to create directory $parentPath")
        val virtualFile = parentDir.findChild(ioFile.name) ?: parentDir.createChildData(this, ioFile.name)
        VfsUtil.saveText(virtualFile, content)
        return virtualFile
    }

    private fun writeBinaryFile(filePath: String, content: ByteArray): VirtualFile {
        val ioFile = File(filePath)
        val parentPath = ioFile.parent ?: throw IllegalArgumentException("Parent directory not found for $filePath")
        val parentDir = VfsUtil.createDirectoryIfMissing(parentPath)
            ?: throw IllegalStateException("Unable to create directory $parentPath")
        val virtualFile = parentDir.findChild(ioFile.name) ?: parentDir.createChildData(this, ioFile.name)
        virtualFile.setBinaryContent(content)
        return virtualFile
    }

    private fun String.asJsStringLiteral(): String =
        buildString(length + 2) {
            append('\'')
            this@asJsStringLiteral.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
            append('\'')
        }

    fun dispose() {
        openFileQuery.dispose()
        requestRefreshQuery.dispose()
        generateMigrationQuery.dispose()
        saveMigrationQuery.dispose()
        saveFileQuery.dispose()
        createMigrationQuery.dispose()
        browseDirectoryQuery.dispose()
        deleteMigrationQuery.dispose()
        saveErLayoutQuery.dispose()
        dismissPendingMigrationQuery.dispose()
        openRelatedSchemaSourceQuery.dispose()
        saveDiffSelectionQuery.dispose()
    }
}
