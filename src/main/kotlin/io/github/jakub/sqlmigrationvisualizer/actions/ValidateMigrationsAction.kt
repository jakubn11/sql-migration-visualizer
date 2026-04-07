package io.github.jakub.sqlmigrationvisualizer.actions

import io.github.jakub.sqlmigrationvisualizer.MigrationVisualizerToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to validate all migration files for consistency.
 */
class ValidateMigrationsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Refresh also triggers validation
        MigrationVisualizerToolWindowFactory.getPanel(project)?.refreshData()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
