plugins {
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.changelog") version "2.2.1"
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
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }

        vendor {
            name = "Schema Tools"
            email = "jakubnl94@gmail.com"
            url = "https://github.com/jakubn11/sql-migration-visualizer"
        }

        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(providers.gradleProperty("pluginVersion").get()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    org.jetbrains.changelog.Changelog.OutputType.HTML,
                )
            }
        }
    }

    pluginVerification {
        ides {
            ide("IC", "2024.1")
            ide("IC", "2024.3")
            ide("IC", "2025.2")
        }
    }
}

changelog {
    version = providers.gradleProperty("pluginVersion")
    path = file("CHANGELOG.md").canonicalPath
    headerParserRegex = """(\d+\.\d+\.\d+)""".toRegex()
    groups.empty()
    repositoryUrl = "https://github.com/jakubn11/sql-migration-visualizer"
}

tasks {
    wrapper {
        gradleVersion = "8.13"
    }

    test {
        useJUnitPlatform()
    }
}
