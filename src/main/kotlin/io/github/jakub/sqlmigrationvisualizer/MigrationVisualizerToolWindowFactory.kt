package io.github.jakub.sqlmigrationvisualizer

import io.github.jakub.sqlmigrationvisualizer.services.VisualizerPanelManager
import io.github.jakub.sqlmigrationvisualizer.ui.VisualizerPanel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.content.ContentFactory
import javax.swing.Icon

/**
 * Factory that creates the SQL Migration Visualizer tool window.
 * Registered in plugin.xml as the factoryClass for the "SQL Migrations" tool window.
 */
class MigrationVisualizerToolWindowFactory : ToolWindowFactory, DumbAware {

    private val activeIcon: Icon by lazy {
        IconLoader.getIcon("/icons/pluginIconSelected.png", javaClass)
    }

    private val inactiveIcon: Icon by lazy {
        IconLoader.getIcon("/icons/pluginIconInactive.png", javaClass)
    }

    companion object {
        fun getPanel(project: Project): VisualizerPanel? =
            project.getService(VisualizerPanelManager::class.java).getPanel()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = VisualizerPanel(project, toolWindow.disposable)
        project.getService(VisualizerPanelManager::class.java).setPanel(panel)

        val content = ContentFactory.getInstance().createContent(
            panel.component,
            "Schema Timeline",
            false
        )
        updateIcons(project, toolWindow, content)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    updateIcons(project, toolWindow, content)
                }
            }
        )
        toolWindow.contentManager.addContent(content)
    }

    private fun updateIcons(project: Project, toolWindow: ToolWindow, content: com.intellij.ui.content.Content) {
        val isSelected = ToolWindowManager.getInstance(project).activeToolWindowId == toolWindow.id
        val icon = if (isSelected) activeIcon else inactiveIcon
        toolWindow.setIcon(icon)
        content.icon = icon
    }
}
