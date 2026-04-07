package io.github.jakub.sqlmigrationvisualizer.actions

import io.github.jakub.sqlmigrationvisualizer.MigrationVisualizerToolWindowFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action to re-scan the project for migration files and refresh the visualization.
 */
class RefreshMigrationsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        MigrationVisualizerToolWindowFactory.getPanel(project)?.refreshData()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
