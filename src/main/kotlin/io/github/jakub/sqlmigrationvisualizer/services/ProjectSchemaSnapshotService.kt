package io.github.jakub.sqlmigrationvisualizer.services

import io.github.jakub.sqlmigrationvisualizer.model.BaselineSchemaFile
import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import io.github.jakub.sqlmigrationvisualizer.parser.MigrationScanner
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

data class ProjectSchemaSnapshot(
    val migrations: List<MigrationFile> = emptyList(),
    val baselineStatements: List<String> = emptyList(),
    val baselineFiles: List<BaselineSchemaFile> = emptyList()
)

@Service(Service.Level.PROJECT)
class ProjectSchemaSnapshotService(
    private val project: Project
) : Disposable {

    private val scanner = MigrationScanner(project)
    private val lock = Any()

    @Volatile
    private var migrationsDirty = true

    @Volatile
    private var baselineDirty = true

    @Volatile
    private var cachedMigrations: List<MigrationFile> = emptyList()

    @Volatile
    private var cachedBaselineStatements: List<String> = emptyList()

    @Volatile
    private var cachedBaselineFiles: List<BaselineSchemaFile> = emptyList()

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    synchronized(lock) {
                        events.forEach(::invalidateForEvent)
                    }
                }
            }
        )
    }

    fun invalidate() {
        synchronized(lock) {
            migrationsDirty = true
            baselineDirty = true
        }
    }

    fun getSnapshot(): ProjectSchemaSnapshot =
        ReadAction.compute<ProjectSchemaSnapshot, Throwable> {
            getSnapshotUnderReadAction()
        }

    fun getSnapshotUnderReadAction(): ProjectSchemaSnapshot {
        if (!migrationsDirty && !baselineDirty) {
            return ProjectSchemaSnapshot(
                migrations = cachedMigrations,
                baselineStatements = cachedBaselineStatements,
                baselineFiles = cachedBaselineFiles
            )
        }

        synchronized(lock) {
            if (migrationsDirty) {
                cachedMigrations = scanner.scanMigrations()
                migrationsDirty = false
            }
            if (baselineDirty) {
                cachedBaselineFiles = scanner.scanBaselineSchemaFiles()
                cachedBaselineStatements = cachedBaselineFiles.flatMap { it.createStatements }
                baselineDirty = false
            }
            return ProjectSchemaSnapshot(
                migrations = cachedMigrations,
                baselineStatements = cachedBaselineStatements,
                baselineFiles = cachedBaselineFiles
            )
        }
    }

    fun findRelatedSchemaFile(tableName: String, columnName: String? = null): String? =
        ReadAction.compute<String?, Throwable> {
            val snapshot = getSnapshotUnderReadAction()
            val normalizedTable = tableName.trim().lowercase()
            if (normalizedTable.isEmpty()) return@compute null

            val directMatches = snapshot.baselineFiles.filter { file ->
                file.createStatements.any { statement ->
                    Regex(
                        """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"\[]?${Regex.escape(tableName)}[`"\]]?(?:\s|\()""",
                        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                    ).containsMatchIn(statement)
                }
            }
            if (directMatches.isEmpty()) return@compute null

            val normalizedColumn = columnName?.trim()?.lowercase().orEmpty()
            val preferredMatch = directMatches.firstOrNull { file ->
                normalizedColumn.isNotEmpty() &&
                    Regex("""\b${Regex.escape(columnName!!.trim())}\b""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(file.rawContent)
            }
            preferredMatch?.filePath ?: directMatches.first().filePath
        }

    private fun invalidateForEvent(event: VFileEvent) {
        val basePath = project.basePath ?: return
        val path = event.path
        if (!path.startsWith(basePath)) return
        val fileName = path.substringAfterLast('/')

        when {
            MigrationScanner.isMigrationFileName(fileName) -> migrationsDirty = true
            MigrationScanner.isSchemaFileName(fileName) -> baselineDirty = true
            path.endsWith(".sql") || path.endsWith(".ddl") -> {
                migrationsDirty = true
                baselineDirty = true
            }
        }
    }

    override fun dispose() = Unit
}
