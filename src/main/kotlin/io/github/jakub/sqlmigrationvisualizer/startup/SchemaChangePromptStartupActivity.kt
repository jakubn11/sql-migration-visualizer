package io.github.jakub.sqlmigrationvisualizer.startup

import io.github.jakub.sqlmigrationvisualizer.watcher.SchemaChangePromptService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SchemaChangePromptStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(SchemaChangePromptService::class.java)
    }
}
