# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-04-21

### Added

### Changed

### Fixed


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

[Unreleased]: https://github.com/jakubn11/sql-migration-visualizer/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/jakubn11/sql-migration-visualizer/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/jakubn11/sql-migration-visualizer/releases/tag/v1.0.0
