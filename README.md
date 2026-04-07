# SQL Migration Visualizer

<p align="center">
  <img src="src/main/resources/icons/panelIcon.png" alt="SQL Migration Visualizer logo" width="240">
</p>

<p align="center">
  Explore SQL migration history, inspect schema diffs, and generate the next migration without leaving IntelliJ IDEA.
</p>

<p align="center">
  Built for teams working with versioned SQL migrations, SQLDelight layouts, and baseline schema files.
</p>

## What It Does

SQL Migration Visualizer turns your migration files and schema definitions into an interactive view inside IntelliJ-based IDEs. Instead of manually jumping between migration folders, schema snapshots, and generated SQL, you get one place to understand how your database evolved and what should happen next.

It is especially useful when you want to:

- trace how a table changed across versions
- compare two schema states side by side
- validate migration history before something breaks
- spot schema changes and draft a migration faster

## Highlights

- Timeline view for browsing schema versions, change summaries, and migration sources
- Schema diff tools for added, removed, and modified tables and columns
- ER diagram view with zoom and draggable tables
- Migration validation for gaps, duplicates, invalid alters, and inconsistent history
- Pending migration suggestions when schema files change on save
- Migration creation and management directly from the IDE
- SQL generation support for Generic SQL, PostgreSQL, and MySQL / MariaDB workflows

## Supported Project Layouts

The plugin auto-detects common migration and schema locations, including:

- `src/main/sqldelight`
- `src/commonMain/sqldelight`
- `src/androidMain/sqldelight`
- `db/migrations`
- `database/migrations`
- `migrations`
- `src/main/resources/db/migrations`
- `src/main/resources/schema`

It also recognizes common migration naming patterns such as `1.sql`, `2.sqm`, and `V3__add_users.sql`.

## IntelliJ Plugin Features

### Visualize Schema History

Follow your database evolution version by version through a timeline view that makes migration history easier to scan and reason about.

### Compare Versions

Inspect differences between schema versions to quickly see renamed tables, changed columns, and structural drift.

### Generate a Migration Draft

When a schema change is detected, the plugin can prepare SQL for the next migration and adapt output for your selected SQL dialect.

### Catch Problems Early

Validation helps surface missing versions, duplicate migrations, and other consistency issues before they become runtime surprises.

## Development

Requirements:

- JDK 17
- IntelliJ Platform `2024.1`

Useful commands:

```bash
./gradlew test
./gradlew runIde
./gradlew build
```

## Why This Project Exists

Database migrations are one of those areas where a small mistake can quietly grow into a hard-to-debug production issue. This plugin is meant to make migration history more visible, schema changes more reviewable, and migration authoring less tedious for developers who live inside JetBrains IDEs.

## Status

Current version: `1.0.0`

This repository contains the source for the IntelliJ plugin. If you want to build, test, or iterate on the plugin locally, the Gradle tasks above are the quickest way in.
