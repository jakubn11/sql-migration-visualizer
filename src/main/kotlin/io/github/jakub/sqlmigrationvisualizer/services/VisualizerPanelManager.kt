package io.github.jakub.sqlmigrationvisualizer.services

import io.github.jakub.sqlmigrationvisualizer.ui.VisualizerPanel
import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class VisualizerPanelManager {
    @Volatile
    private var panel: VisualizerPanel? = null

    fun setPanel(panel: VisualizerPanel) {
        this.panel = panel
    }

    fun getPanel(): VisualizerPanel? = panel

    fun clearPanel(panel: VisualizerPanel) {
        if (this.panel === panel) {
            this.panel = null
        }
    }
}
