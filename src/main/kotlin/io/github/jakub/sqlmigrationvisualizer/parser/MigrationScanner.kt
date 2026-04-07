package io.github.jakub.sqlmigrationvisualizer.parser

import io.github.jakub.sqlmigrationvisualizer.model.BaselineSchemaFile
import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor

/**
 * Scans the project for versioned migration files and baseline schema files.
 * Supports SQLDelight conventions as well as common generic SQL migration layouts.
 */
class MigrationScanner(private val project: Project) {

    companion object {
        private val migrationPatterns = listOf(
            Regex("""^(\d+)\.(sqm|sql)$""", RegexOption.IGNORE_CASE),
            Regex("""^(\d+)[._-].+\.(sqm|sql)$""", RegexOption.IGNORE_CASE),
            Regex("""^[Vv](\d+)__.+\.(sqm|sql)$""", RegexOption.IGNORE_CASE)
        )
        private val schemaExtensions = setOf("sq", "sql", "ddl")

        internal fun parseMigrationVersion(fileName: String): Int? =
            migrationPatterns.firstNotNullOfOrNull { pattern ->
                pattern.matchEntire(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }

        internal fun isMigrationFileName(fileName: String): Boolean =
            parseMigrationVersion(fileName) != null

        internal fun isSchemaFileName(fileName: String): Boolean {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return extension in schemaExtensions && !isMigrationFileName(fileName)
        }

        internal fun detectPreferredMigrationExtension(existingMigrationPaths: List<String>): String =
            existingMigrationPaths
                .asSequence()
                .map { path -> path.substringAfterLast('.', "").lowercase() }
                .firstOrNull { it == "sql" || it == "sqm" }
                ?: "sql"

        internal fun parseStatements(content: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var index = 0
            var inSingleQuote = false
            var inDoubleQuote = false
            var inBacktickQuote = false
            var inBracketQuote = false
            var inLineComment = false
            var inBlockComment = false
            var dollarQuoteTag: String? = null

            while (index < content.length) {
                val ch = content[index]
                val next = content.getOrNull(index + 1)

                if (inLineComment) {
                    if (ch == '\n') {
                        inLineComment = false
                        current.append(ch)
                    }
                    index += 1
                    continue
                }

                if (inBlockComment) {
                    if (ch == '*' && next == '/') {
                        inBlockComment = false
                        index += 2
                    } else {
                        index += 1
                    }
                    continue
                }

                val activeDollarQuote = dollarQuoteTag
                if (activeDollarQuote != null) {
                    if (content.startsWith(activeDollarQuote, index)) {
                        current.append(activeDollarQuote)
                        index += activeDollarQuote.length
                        dollarQuoteTag = null
                    } else {
                        current.append(ch)
                        index += 1
                    }
                    continue
                }

                if (inSingleQuote) {
                    current.append(ch)
                    if (ch == '\'' && next == '\'') {
                        current.append(next)
                        index += 2
                    } else {
                        if (ch == '\'') inSingleQuote = false
                        index += 1
                    }
                    continue
                }

                if (inDoubleQuote) {
                    current.append(ch)
                    if (ch == '"' && next == '"') {
                        current.append(next)
                        index += 2
                    } else {
                        if (ch == '"') inDoubleQuote = false
                        index += 1
                    }
                    continue
                }

                if (inBacktickQuote) {
                    current.append(ch)
                    if (ch == '`') inBacktickQuote = false
                    index += 1
                    continue
                }

                if (inBracketQuote) {
                    current.append(ch)
                    if (ch == ']') inBracketQuote = false
                    index += 1
                    continue
                }

                if (ch == '-' && next == '-') {
                    inLineComment = true
                    index += 2
                    continue
                }

                if (ch == '/' && next == '*') {
                    inBlockComment = true
                    index += 2
                    continue
                }

                val detectedDollarQuote = detectDollarQuoteStart(content, index)
                if (detectedDollarQuote != null) {
                    dollarQuoteTag = detectedDollarQuote
                    current.append(detectedDollarQuote)
                    index += detectedDollarQuote.length
                    continue
                }

                when (ch) {
                    '\'' -> {
                        inSingleQuote = true
                        current.append(ch)
                    }
                    '"' -> {
                        inDoubleQuote = true
                        current.append(ch)
                    }
                    '`' -> {
                        inBacktickQuote = true
                        current.append(ch)
                    }
                    '[' -> {
                        inBracketQuote = true
                        current.append(ch)
                    }
                    ';' -> {
                        val statement = current.toString().trim()
                        if (statement.isNotBlank()) {
                            statements += statement
                        }
                        current.clear()
                    }
                    else -> current.append(ch)
                }
                index += 1
            }

            val trailingStatement = current.toString().trim()
            if (trailingStatement.isNotBlank()) {
                statements += trailingStatement
            }

            return statements
        }

        private fun detectDollarQuoteStart(content: String, index: Int): String? {
            if (content[index] != '$') return null
            val closing = content.indexOf('$', startIndex = index + 1)
            if (closing == -1) return null
            val candidate = content.substring(index, closing + 1)
            return if (candidate.matches(Regex("""\$[A-Za-z0-9_]*\$"""))) candidate else null
        }
    }

    /**
     * Scan the entire project for versioned migration files.
     * Returns them sorted by version number.
     */
    fun scanMigrations(): List<MigrationFile> {
        val baseDir = project.baseDir ?: return emptyList()
        val migrationFiles = mutableListOf<MigrationFile>()

        VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                // Skip build directories, hidden directories, and VCS
                val path = file.path
                if (file.isDirectory) {
                    val name = file.name
                    return name != "build" && name != ".gradle" && name != ".idea" &&
                            name != "node_modules" && !name.startsWith(".")
                }

                val version = parseMigrationVersion(file.name)
                if (version != null) {
                    val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                    val statements = parseStatements(content)
                    migrationFiles.add(
                        MigrationFile(
                            version = version,
                            filePath = file.path,
                            fileName = file.name,
                            statements = statements,
                            rawContent = content
                        )
                    )
                }
                return true
            }
        })

        return migrationFiles.sortedBy { it.version }
    }

    /**
     * Scan the project for schema definition files and extract CREATE TABLE statements
     * to establish the baseline schema (version 0).
     */
    fun scanBaselineSchema(): List<String> {
        return scanBaselineSchemaFiles().flatMap { it.createStatements }
    }

    fun scanBaselineSchemaFiles(): List<BaselineSchemaFile> {
        val baseDir = project.baseDir ?: return emptyList()
        val schemaFiles = mutableListOf<BaselineSchemaFile>()

        VfsUtilCore.visitChildrenRecursively(baseDir, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    val name = file.name
                    return name != "build" && name != ".gradle" && name != ".idea" &&
                            name != "node_modules" && !name.startsWith(".")
                }

                if (isSchemaFileName(file.name)) {
                    val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                    val statements = parseStatements(content)
                    val createStatements = statements
                        .filter { it.trim().uppercase().startsWith("CREATE TABLE") }
                    if (createStatements.isNotEmpty()) {
                        schemaFiles.add(
                            BaselineSchemaFile(
                                filePath = file.path,
                                fileName = file.name,
                                createStatements = createStatements,
                                rawContent = content
                            )
                        )
                    }
                }
                return true
            }
        })

        return schemaFiles.sortedBy { it.filePath }
    }

}
