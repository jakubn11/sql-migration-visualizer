package io.github.jakub.sqlmigrationvisualizer.settings

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VisualizerSettingsTest {

    @Test
    fun `ER table positions survive IntelliJ XML serialization`() {
        val state = VisualizerSettings.State(
            erTablePositions = mapOf(
                "3" to mapOf(
                    "users" to VisualizerSettings.ErTablePosition(x = 12.5, y = -4.0)
                )
            )
        )

        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state),
            VisualizerSettings.State::class.java
        )

        val position = restored.erTablePositions["3"]?.get("users")
        assertNotNull(position)
        assertEquals(12.5, position.x)
        assertEquals(-4.0, position.y)
    }

    @Test
    fun `ER table position has bean-friendly defaults for persisted state`() {
        val position = VisualizerSettings.ErTablePosition()

        assertEquals(0.0, position.x)
        assertEquals(0.0, position.y)

        position.x = 8.0
        position.y = 13.0

        assertEquals(8.0, position.x)
        assertEquals(13.0, position.y)
    }

    @Test
    fun `loaded state is sanitized to supported settings values`() {
        val settings = VisualizerSettings()

        settings.loadState(
            VisualizerSettings.State(
                defaultTab = "missing",
                preferredSqlDialect = "sqlite",
                erLayoutColumns = 999,
                erTablePositions = mapOf(
                    "1" to mapOf(
                        " users " to VisualizerSettings.ErTablePosition(Double.NaN, Double.POSITIVE_INFINITY)
                    )
                ),
                lastDiffFromVersion = -4,
                lastDiffToVersion = -1,
                searchResultLimit = 2,
                defaultMigrationDirectory = " migrations ",
                additionalMigrationDirectories = " db/migrations ",
                migrationFileNamePattern = "   "
            )
        )

        val state = settings.state
        val position = state.erTablePositions["1"]?.get("users")

        assertEquals("timeline", state.defaultTab)
        assertEquals("generic", state.preferredSqlDialect)
        assertEquals(10, state.erLayoutColumns)
        assertNotNull(position)
        assertEquals(0.0, position.x)
        assertEquals(0.0, position.y)
        assertEquals(0, state.lastDiffFromVersion)
        assertEquals(0, state.lastDiffToVersion)
        assertEquals(5, state.searchResultLimit)
        assertEquals("migrations", state.defaultMigrationDirectory)
        assertEquals("db/migrations", state.additionalMigrationDirectories)
        assertEquals("{version}", state.migrationFileNamePattern)
    }

    @Test
    fun `saving ER layout trims keys and drops non-finite coordinates`() {
        val settings = VisualizerSettings()

        settings.saveErLayout(
            " 2 ",
            mapOf(
                " orders " to VisualizerSettings.ErTablePosition(42.0, Double.NEGATIVE_INFINITY),
                " " to VisualizerSettings.ErTablePosition(1.0, 1.0)
            )
        )

        val versionLayout = settings.state.erTablePositions["2"]
        val position = versionLayout?.get("orders")

        assertEquals(setOf("orders"), versionLayout?.keys)
        assertNotNull(position)
        assertEquals(42.0, position.x)
        assertEquals(0.0, position.y)
    }
}
