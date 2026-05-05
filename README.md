<div align="center">

<img src="src/main/resources/icons/panelIcon.png" alt="SQL Migration Visualizer" width="108">

# SQL Migration Visualizer

**See your database history inside IntelliJ IDEA.**

Explore schema evolution, compare versions, validate migrations, and draft the next SQL migration without bouncing between folders and files.

<p>
  <img alt="License" src="https://img.shields.io/badge/license-GPLv3-6aa6ff?style=flat-square&labelColor=1a2030">
  <img alt="Platform" src="https://img.shields.io/badge/intellij-2024.1%2B-6aa6ff?style=flat-square&labelColor=1a2030">
  <img alt="Kotlin" src="https://img.shields.io/badge/kotlin-2.0.21-6aa6ff?style=flat-square&labelColor=1a2030">
  <img alt="Java" src="https://img.shields.io/badge/java-17-6aa6ff?style=flat-square&labelColor=1a2030">
</p>

</div>

## Features

- Timeline view — browse every schema version with change summaries and source file actions
- Schema diff — compare two versions side by side (added, removed, modified tables and columns)
- ER diagram — interactive entity-relationship canvas with zoom and draggable tables
- Migration validation — detect version gaps, duplicates, invalid ALTER targets, and FK inconsistencies
- Pending migration suggestions — detect baseline schema drift and prompt to review a draft migration
- Risk analysis — score migrations LOW / MEDIUM / HIGH (drops, missing defaults, type narrowing)
- Migration generator — produce draft SQL from schema differences across PostgreSQL, MySQL, and generic dialects
- Create, edit, and delete migration files without leaving the IDE
- Auto-detection of common migration directory layouts (SQLDelight, Flyway, generic)
- Project-level settings for directories, SQL dialect, and default tab

## Requirements

- IntelliJ IDEA 2024.1 or later (Community or Ultimate)
- JDK 17

## Installation

See [INSTALL.md](INSTALL.md) for full instructions.

**From source:**
```bash
./gradlew buildPlugin   # produces build/distributions/*.zip
```
Install the ZIP via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Supported Layouts

The plugin auto-detects common migration and schema locations:

| Pattern | Example |
|---------|---------|
| SQLDelight | `src/main/sqldelight/`, `src/commonMain/sqldelight/` |
| Flyway | `src/main/resources/db/migrations/` |
| Generic | `db/migrations/`, `database/migrations/`, `migrations/` |
| Schema | `src/main/resources/schema/` |

Recognised file naming:

| Pattern | Example |
|---------|---------|
| Plain version | `1.sql`, `2.sqm` |
| Version + name | `12_add_users.sql` |
| Flyway | `V3__create_orders.sql` |

## Usage

Open the **SQL Migrations** panel at the bottom of the IDE. The plugin scans your project on open and after every file save.

**Create Migration dialog:**

| Key | Action |
|-----|--------|
| Cmd+Enter / Ctrl+Enter | Submit the migration (create or save) |
| Tab | Accept the selected SQL autocomplete suggestion |
| ↑ / ↓ | Navigate autocomplete suggestions |
| Esc | Close autocomplete |

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| No migrations appear | Check that your migration directory matches one of the supported layouts, or set it manually in **Settings → Tools → SQL Migration Visualizer**. |
| Timeline shows gaps | Versions must be contiguous integers. Run **Validate** to see the exact gap locations. |
| ER diagram is empty | The diagram requires at least one parsed schema version. Ensure your migration files contain valid `CREATE TABLE` statements. |
| Plugin panel is missing | Open **View → Tool Windows → SQL Migrations** or reopen the project. |
| Changes not reflected | Click **Refresh** in the panel toolbar or re-save a migration file. |

## License

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
