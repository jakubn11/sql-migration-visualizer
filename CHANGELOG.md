# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- SQLite as a first-class generator dialect (Settings → Tools → SQL Migration Visualizer → Preferred generator dialect). SQLite cannot `ALTER COLUMN` in place, so any column type / nullability / default change automatically falls back to the standard 12-step rebuild dance — `PRAGMA foreign_keys=OFF` + `BEGIN TRANSACTION` + new table + `INSERT … SELECT` + drop + rename + `COMMIT` + `PRAGMA foreign_keys=ON` — with comment guidance to recreate any indexes, triggers, or views that referenced the old table

## [1.3.1] - 2026-05-16

### Changed
- Pending Migration banner now uses the same success green as the validation panel — banner tint, badge, icon, and divider all aligned on `--color-success` instead of a teal that competed with it
- Review Draft button uses the standard ghost button style, matching Cancel Pending — removes the one-off blue-text treatment that made it look like a different control type
- Toned down the small primary button's drop shadow so it no longer overwhelms the ghost buttons next to it in tight banners
- Unified the corner radius of `.btn-sm` and `.risk-badge` so the buttons and the High/Medium/Low risk pill on the Pending Migration row look like the same family of controls
- Removed the redundant blue left border on info-severity validation cards (the severity badge already conveys the level)
- Modal dialogs now size to their content instead of always filling 80vh — short forms (e.g. Review Pending Migration with a small SQL snippet) no longer leave a large empty area below the content; long content still scrolls within the same max-height
- Fixed the SQL editor opening at an inflated height in the Create/Review Migration dialog — the autosize measurement ran while the modal was still hidden (zero width) and produced a hundreds-of-pixels-tall textarea; it now measures after the modal is visible
- The Pending Migration banner and the "Create Suggested Migration" header button now clear correctly after the suggested migration is created — the pending-suggestion state is re-evaluated as soon as the new migration file is written, instead of only on the next explicit Save action
- Timeline version dot no longer breaks the `…1234` truncation onto two lines for long versions — added `white-space: nowrap` and trimmed the template-literal whitespace that was letting the ellipsis wrap to its own line
- Search-result and validation `v<N>` chips now use inline-flex centering, so the version text sits properly in the middle of the pill instead of riding the baseline
- Table-card column rows now lay out on a single line — badges, type token, and Lineage/SQL buttons no longer wrap below the column name when the card is narrow; the column name truncates with an ellipsis if it's too long
- ER diagram no longer shows a horizontal scrollbar at moderate tool-window widths — the toolbar (version, zoom, fit, export, legend) now wraps to a second line when it would otherwise overflow, instead of only wrapping below the compact-width breakpoint
- Schema table cards now require ~480px of card width before they pack horizontally — the previous 260px minimum was forcing column names to truncate to a few characters (`ord…`, `pr…`) when 4 cards lined up on a wide tool window; the responsive grid now caps to 2–3 cards per row on typical widths so column names, FK target, type, and per-column action buttons all fit in one line
- Removed the Name field from the Edit Migration dialog — the filename of an existing migration can't be changed through this dialog, so the disabled input was just visual noise
- Removed the per-column "SQL" button from Timeline table cards — the same "Open SQL" affordance is still available on each table-card header, so the per-column button was redundant noise; column rows are now lighter and have more room for names and badges
- Table-history summary cards no longer cap the introduced/removed column chips at four — when a table is introduced with eight columns, all eight chips are shown instead of `+id +name +username +birthday` and silently dropping the rest
- Added-column chips (`+name`) are now tinted green and removed-column chips (`−birthday`) tinted red in the table history view, instead of both rendering as the same neutral gray pill
- Column lineage cards for introduced/removed columns no longer duplicate `type` and `NULL allowed` as both a bullet-joined summary line and a pair of chips below — the chips were a literal copy of the summary and added nothing
- `BASELINE` and `ADDED` status badges in table/column history no longer share the same green tint — baseline is now blue (informational, "this existed at the starting point") and added stays green (something genuinely new)
- Schema Diff summary card simplified — dropped the long instructional subtitle and the four bordered counter pills, replaced with a compact `1 table changed · +1 column · −1 column` line; zero counts are hidden, and the risk badge moved to the top-right corner. Copy Summary button removed (the same info is one click away in the migration draft).
- `CHANGED` / `ADDED` status pills in both the Schema Diff table headers (`.status-tag`) and the Timeline table cards (`.table-status-pill`) are now horizontally centered — they were `<span>`s with padding and `min-height` but no `display: inline-flex` / `justify-content: center`, so `letter-spacing: 0.5px` was pushing text off-center.
- Centered text horizontally inside every pill/chip/tag/badge class — `.migration-suggestion-badge`, `.empty-state-badge`, `.preview-table-tag`, `.table-history-focus-chip`, `.table-history-change-chip`, `.diff-mini-pill`, `.diff-change-chip`, and `.validation-pill` all now have `display: inline-flex; justify-content: center; line-height: 1` for consistent vertical+horizontal centering across the design system.
- Schema Diff summary no longer stacks per-risk-item "Column dropped" cards below the count line — the risk headline (e.g. `High-risk diff review recommended`) and the colored risk badge already signal the severity, and the per-table breakdown is right below in the diff body, so the list was duplicating information.
- Removed the "Changes in this version" section from the Timeline migration detail view — the timeline strip and the table cards already convey which tables changed, and the one-line `~ Table user modified (+birthday_test; −birthday)` summary was the third place the same information appeared. The associated JS, HTML, CSS, and collapsible-section bookkeeping were all stripped.
- Schema Diff summary card no longer renders the `<risk-level> diff review recommended` headline below the count line — the colored `High risk` / `Moderate risk` / `Low risk` badge in the top-right corner already signals severity, so the sentence was duplicating what the badge says.
- Schema Diff summary card removed entirely — the title (`Version A → Version B`), the count line, and the risk badge were all duplicating information the per-table breakdown below already shows. The `#diff-summary:empty` rule keeps the container fully collapsed when no card is rendered, and the dead `renderSummary` JS function plus its CSS rules were stripped.
- Validation summary card is now suppressed entirely when there are no issues — the `Validated N migration(s). All checks passed ✓` line plus three `0` pills was redundant with the existing "All checks passed" empty state below; the empty state now also surfaces the migration count
- When issues exist, the validation summary only shows non-zero pills (e.g. just `1 error` instead of `1 error · 0 warnings · 0 info`)
- Removed the thin grey horizontal divider running underneath the Timeline / Schema Diff / ER Diagram / Validation tab bar — the active-tab blue underline is already enough visual separation
- ER diagram: the canvas now sizes from its own laid-out CSS box (with the ER panel clipped to `overflow: hidden`), so the chicken-and-egg between canvas width and the panel's scrollbar no longer produces a stray horizontal scroll
- ER diagram: the viewport now always fits to the table layout on render — previously, when a project had stored table positions from earlier sessions, `fitToView()` was skipped and the saved layout rendered with the initial pan of (0, 0), leaving content stuck in the top-left corner

### Added
- ER diagram: click-drag the canvas to pan around the diagram (zoom remains button-only; table dragging stays disabled, so the layout is stable)
- ER diagram PNG export now strips any hover or focus highlights so the image is clean, and stamps a subtle white "SQL Migration Visualizer" watermark with the plugin logo in the bottom-right corner
- ER diagram zoom is now button-only (+, -, fit) — removed wheel zoom, canvas pan, and table-drag gestures for a more predictable view, especially on trackpads and touch surfaces

## [1.3.0] - 2026-05-16

### Added
- Confirmation dialog when closing the Create Migration dialog with unsaved SQL content — prevents accidentally discarding in-progress migrations
- Escape key now closes the Create Migration dialog (routed through the same unsaved-content confirmation) and dismisses the confirmation dialog itself
- One-click "Renumber to v&lt;N&gt;" quick-fix on duplicate-version validation issues — resolves the common feature-branch conflict where two branches landed migrations with the same version, and the button shows the exact target version so you know what to expect before confirming

### Changed
- Buttons now have a visible disabled state, and the Renumber quick-fix button disables itself while a rename is in flight to prevent accidental double-fires

### Fixed
- Prevented OOM crash when validating projects that mix sequential and Flyway-style timestamp versions — large version gaps are now summarised instead of enumerated
- Text caret now reliably reappears in inputs and the SQL editor when the JCEF browser regains focus or after intermittent focus desyncs
- Timeline version dots stay a consistent 38px circle for all version lengths — long versions are truncated to the last digits inside the dot (with the full version available on hover), and the migration filename label has more room to display in full

## [1.2.7] - 2026-05-06

### Changed
- Removed the redundant context card from the Create Migration dialog
- Create Migration dialog scrollbar now follows the SQL editor caret as the editor grows
- Aligned main plugin chrome, search/stat rows, tabs, and panel content to shared horizontal padding
- Unified plugin control styling across buttons, dropdowns, search fields, steppers, and status pills

### Fixed
- Made editable input caret/cursor styling explicit to avoid intermittent missing carets

## [1.2.6] - 2026-05-06

### Changed
- Added field-level required validation for SQL Statements in the Create Migration dialog
- Version input in the Create Migration dialog now falls back to 0 instead of staying empty
- Improved search field icon spacing

### Fixed
- Reduced Create Migration SQL editor scroll lag by avoiding syntax-highlight rerenders during scroll

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
