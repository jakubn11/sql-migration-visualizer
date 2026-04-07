package io.github.jakub.sqlmigrationvisualizer.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TimelineUiContractTest {

    @Test
    fun `timeline history keeps explicit clear controls without renderer clearing focus`() {
        val timelineJs = resourceText("/web/timeline.js")

        assertContains(timelineJs, "clearTableHistory()")
        assertContains(timelineJs, "Clear Focus")
        assertFalse(timelineJs.contains("state.ui.timelineFocus = null"))
    }

    @Test
    fun `timeline history keeps current table history controls`() {
        val panelSource = resourceText("/web/timeline.js")
        assertContains(panelSource, "Table History")
        assertContains(panelSource, "Selected table is not in this version")
        assertContains(panelSource, "Open SQL")
    }

    private fun resourceText(path: String): String =
        javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Missing resource: $path")
}
