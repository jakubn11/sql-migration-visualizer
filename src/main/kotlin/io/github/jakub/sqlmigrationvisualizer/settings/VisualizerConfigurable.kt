package io.github.jakub.sqlmigrationvisualizer.settings

import io.github.jakub.sqlmigrationvisualizer.MigrationVisualizerToolWindowFactory
import io.github.jakub.sqlmigrationvisualizer.generator.SqlDialect
import io.github.jakub.sqlmigrationvisualizer.watcher.SchemaChangePromptService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class VisualizerConfigurable(private val project: Project) : Configurable {

    private data class DefaultTabOption(
        val id: String,
        val label: String
    ) {
        override fun toString(): String = label
    }

    private val settings = VisualizerSettings.getInstance(project)
    private val defaultTabOptions = listOf(
        DefaultTabOption("timeline", "Timeline"),
        DefaultTabOption("diff", "Schema Diff"),
        DefaultTabOption("er-diagram", "ER Diagram"),
        DefaultTabOption("validation", "Validation")
    )
    private val dialectOptions = SqlDialect.entries
    private lateinit var panel: DialogPanel
    private lateinit var defaultTabCombo: ComboBox<DefaultTabOption>
    private lateinit var dialectCombo: ComboBox<SqlDialect>

    override fun getDisplayName(): String = "SQL Migration Visualizer"

    override fun createComponent(): JComponent {
        defaultTabCombo = ComboBox(defaultTabOptions.toTypedArray())
        dialectCombo = ComboBox(dialectOptions.toTypedArray())

        panel = panel {
            group("SQL Dialect") {
                row("Preferred generator dialect:") {
                    cell(dialectCombo)
                        .comment("Used when generating or suggesting migrations. Parsing still stays best-effort across mixed SQL.")
                }
            }

            group("Timeline") {
                row {
                    checkBox("Show baseline version (v0) in timeline")
                        .bindSelected(settings.state::showBaselineInTimeline)
                }
                row {
                    checkBox("Auto-expand table cards when selecting a version")
                        .bindSelected(settings.state::autoExpandTableCards)
                }
            }

            group("ER Diagram") {
                row {
                    checkBox("Show grid dots on canvas background")
                        .bindSelected(settings.state::erShowGrid)
                }
                row {
                    label("Layout columns:")
                    spinner(0..10, 1)
                        .bindIntValue(settings.state::erLayoutColumns)
                        .comment("0 = auto (square root of table count)")
                }
            }

            group("Schema Diff") {
                row {
                    checkBox("Show unchanged columns in diff view")
                        .bindSelected(settings.state::diffShowUnchangedColumns)
                }
            }

            group("Search") {
                row {
                    label("Maximum search results:")
                    spinner(5..100, 5)
                        .bindIntValue(settings.state::searchResultLimit)
                }
            }

            group("Workflow") {
                row("Default tab on open:") {
                    cell(defaultTabCombo)
                }
                row {
                    checkBox("Suggest pending migration after explicit save")
                        .bindSelected(settings.state::suggestPendingMigrationOnSave)
                }
                row {
                    checkBox("Remember selected versions in Schema Diff")
                        .bindSelected(settings.state::rememberDiffSelections)
                }
                row {
                    checkBox("Open newly created migration files in the editor")
                        .bindSelected(settings.state::autoOpenCreatedMigration)
                }
                row {
                    checkBox("Confirm before deleting migration files")
                        .bindSelected(settings.state::confirmBeforeDeleteMigration)
                }
                row("Preferred migration directory:") {
                    textField()
                        .bindText(settings.state::defaultMigrationDirectory)
                        .comment("Leave empty to auto-detect from existing migration files or your current schema file location.")
                }
                row("Additional migration directories:") {
                    textField()
                        .bindText(settings.state::additionalMigrationDirectories)
                        .comment("Optional hints for uncommon projects. Separate multiple entries with commas or new lines.")
                }
                row("Migration file naming pattern:") {
                    textField()
                        .bindText(settings.state::migrationFileNamePattern)
                        .comment("Supported placeholders: {version}, {name}, {timestamp}, {extension}. Example: V{version}__{name}")
                }
            }

            group("Validation") {
                row {
                    checkBox("Run validation automatically on refresh")
                        .bindSelected(settings.state::validateOnRefresh)
                }
            }

            group("Maintenance") {
                row {
                    button("Reset Cached Baseline") {
                        val result = Messages.showYesNoDialog(
                            project,
                            "Reset the stored baseline snapshot and rebuild it from the current project files?",
                            "SQL Migration Visualizer",
                            "Reset",
                            "Cancel",
                            null
                        )
                        if (result == Messages.YES) {
                            project.getService(SchemaChangePromptService::class.java).resetCachedBaseline()
                            MigrationVisualizerToolWindowFactory.getPanel(project)?.refreshData()
                        }
                    }.comment("Use this if the timeline or pending migration state is out of sync with your current schema files.")
                }
            }
        }
        return panel
    }

    override fun isModified(): Boolean {
        val selectedDefaultTab = (defaultTabCombo.selectedItem as? DefaultTabOption)?.id ?: "timeline"
        val selectedDialect = (dialectCombo.selectedItem as? SqlDialect)?.id ?: SqlDialect.GENERIC.id
        return panel.isModified() ||
            selectedDefaultTab != settings.state.defaultTab ||
            selectedDialect != settings.state.preferredSqlDialect
    }

    override fun apply() {
        panel.apply()
        settings.updateDefaultTab((defaultTabCombo.selectedItem as? DefaultTabOption)?.id ?: "timeline")
        settings.updatePreferredSqlDialect((dialectCombo.selectedItem as? SqlDialect)?.id ?: SqlDialect.GENERIC.id)
        MigrationVisualizerToolWindowFactory.getPanel(project)?.onSettingsChanged()
    }

    override fun reset() {
        panel.reset()
        defaultTabCombo.selectedItem = defaultTabOptions.firstOrNull { it.id == settings.state.defaultTab } ?: defaultTabOptions.first()
        dialectCombo.selectedItem = dialectOptions.firstOrNull { it.id == settings.state.preferredSqlDialect } ?: SqlDialect.GENERIC
    }
}
