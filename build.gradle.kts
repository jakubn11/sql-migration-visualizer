plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        pluginVerifier()
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        description = """
            <h2>SQL Migration Visualizer</h2>
            <p>Explore, validate, and manage SQL schema history directly inside IntelliJ-based IDEs.</p>
            <p>The plugin turns versioned migration files into a visual timeline, helps you inspect how tables evolve,
            and suggests new migrations after you save schema SQL changes.</p>
            <h3>Highlights</h3>
            <ul>
                <li><b>Timeline View</b> — Browse every schema version with table counts, change summaries, and source actions</li>
                <li><b>Schema Diff</b> — Compare versions to see added, removed, and modified tables and columns</li>
                <li><b>ER Diagram</b> — Inspect relationships visually with zoom and draggable tables</li>
                <li><b>Migration Validation</b> — Catch gaps, duplicates, invalid alters, and inconsistent history</li>
                <li><b>Pending Migration Suggestions</b> — Detect saved schema SQL changes and prompt you to review a migration draft</li>
                <li><b>Create, Edit, and Delete</b> — Manage migration files without leaving the IDE</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
        }

        vendor {
            name = "Schema Tools"
            url = "https://github.com/jakub/sql-migration-visualizer"
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }

    test {
        useJUnitPlatform()
    }
}
