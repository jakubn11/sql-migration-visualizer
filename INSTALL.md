# SQL Migration Visualizer — Installation

Visualizes SQL migration history as an interactive timeline inside IntelliJ IDEA.

## From JetBrains Marketplace

> Not yet published. Once available, install directly from **Settings → Plugins → Marketplace** by searching for "SQL Migration Visualizer".

## From a built ZIP

1. Clone the repository and build the plugin:
   ```bash
   git clone https://github.com/jakubn11/sql-migration-visualizer.git
   cd sql-migration-visualizer
   ./gradlew buildPlugin
   ```
   The ZIP is produced at `build/distributions/SQL Migration Visualizer-<version>.zip`.

2. In IntelliJ IDEA, open **Settings → Plugins → ⚙ (gear icon) → Install Plugin from Disk…**

3. Select the ZIP file and click **OK**.

4. Restart IntelliJ IDEA when prompted.

## Requirements

- IntelliJ IDEA 2024.1 or later (Community or Ultimate)
- JDK 17 (for building from source)

## After installing

Open any project that contains SQL migration files. The **SQL Migrations** panel appears at the bottom of the IDE automatically. If it doesn't, open it via **View → Tool Windows → SQL Migrations**.

The plugin auto-detects common migration directory layouts. If your layout isn't detected, configure it manually under **Settings → Tools → SQL Migration Visualizer**.

## Local development

```bash
./gradlew test          # run unit tests
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin   # build distributable ZIP
./gradlew verifyPlugin  # binary compatibility check against the target platform
```

## Updating

Repeat the build and install steps above with the latest source. IntelliJ will replace the old version when you install the new ZIP.

## Uninstalling

Open **Settings → Plugins**, find **SQL Migration Visualizer**, click the gear icon, and choose **Uninstall**. Restart IntelliJ IDEA when prompted. No project files are modified by the plugin.
