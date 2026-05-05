# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.5] - 2026-05-05

### Changed
- Removed the Unreleased changelog section and version comparison links

## [1.2.4] - 2026-05-05

### Changed
- Track shared Claude project guidance and ignore configuration

## [1.2.3] - 2026-05-05

### Changed
- Updated the Suggested Migrations icon in the README feature board to a lightbulb/check mark

## [1.2.2] - 2026-05-05

### Changed
- Simplified the Suggested Migrations icon in the README feature board

## [1.2.1] - 2026-05-05

### Changed
- Refined README feature board icons for clearer migration-related feature representation

## [1.2.0] - 2026-05-05

### Added
- Cmd+Enter (Ctrl+Enter on Windows/Linux) keyboard shortcut to submit the Create Migration form from the SQL editor

### Changed
- Version input auto-sizes based on digit count (44–92 px range) with pointer cursor; enforces digits-only and caps at 9 digits (up to 999 999 999)
- Version stepper (+/−) buttons now have equal padding and gap on both sides
- SQL editor layout: textarea is now in normal flow (drives container height); the syntax-highlight `<pre>` is positioned absolutely behind it — fixes clicking any row to place the cursor there
- SQL editor auto-grows with content using a persistent mirror div for accurate, equal padding on all sides; no max-height cap — the dialog scrollbar handles long content
- SQL editor internal resize handle removed; resize is no longer possible (no longer needed)
- Autocomplete suggestion dropdown positions itself near the caret — above when near the bottom edge, below otherwise
- Caret color in the SQL editor now matches the rest of the app (white)

### Fixed
- Version number displayed in scientific notation (e.g. `1.3e+40`) for very large inputs — now capped at 9 digits with digit-only enforcement
- `submitLabel` option was injected into `innerHTML` unescaped — now passed through `escapeHtml`
- `project.baseDir` deprecated API replaced with `LocalFileSystem.findFileByPath`

### Performance
- Caret mirror div for suggestion positioning is created once and reused across keystrokes instead of created/destroyed each time

## [1.1.0] - 2026-04-21

### Added
- Expanded test coverage for `MigrationValidator`, `SchemaChangeRiskAnalyzer`, and `SqlParser`

## [1.0.1] - 2026-04-21

### Added
- Risk analysis surfaced in schema change detection and pending migration prompts
- CHANGELOG and `bump-version.sh` script
- GPL-3.0 license file

### Changed
- README refreshed with branded feature board SVG and improved layout

## [1.0.0] - 2026-04-21

### Added
- Timeline view — browse every schema version with change summaries and source file actions
- Schema diff — compare two versions side by side (added, removed, modified tables and columns)
- ER diagram — interactive entity-relationship canvas with zoom and draggable tables
- Migration validation — detect version gaps, duplicates, invalid ALTER targets, and FK inconsistencies
- Pending migration suggestions — detect saved baseline schema changes and prompt to review a draft migration
- Risk analysis — score migrations LOW/MEDIUM/HIGH based on drops, required columns without defaults, and type narrowing
- Migration generator — produce draft SQL from schema differences across PostgreSQL, MySQL, and generic dialects
- Create, edit, and delete migration files without leaving the IDE
- Auto-detection of common migration directory layouts (SQLDelight, Flyway, generic)
- Project-level settings for directories, SQL dialect, and default tab
