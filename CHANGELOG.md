# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5.3] - 2026-08-01

### Fixed

- The validation count badge on the tab bar was unreadable in dark theme — white text sat on `--color-error`, which is a light pastel there, for a contrast ratio of 1.97:1 against the WCAG AA floor of 4.5:1 (the warnings-only amber variant was worse at 1.62:1). Both now use `--text-inverse`, reaching 9.44:1 and 11.53:1. The same white-on-pastel mistake in the validation summary icon is fixed with it
- Light theme's `--color-warning` darkened from `#B56A00` to `#A35F00`. The old value failed AA both as badge background with white text (4.18:1) and as body text on the light page background (3.90:1); the new value clears both at 5.01:1 and 4.67:1
- ER diagram table borders no longer look pinched around the title on hover. The header was filled *after* the border was stroked, and since a canvas stroke straddles its path, the fill covered the inner half — so the 2px hover border rendered at 1px alongside the header while the rest of the card kept full width. The border is now stroked last
- Three light-theme tokens failed the WCAG AA 4.5:1 floor as body text and are now darkened, hue preserved: `--text-muted` `#79889E → #627187` (3.36:1 → 4.63:1, the default color for empty states and secondary labels, so the most widely felt), `--color-success` `#0B8D59 → #0A7F50` (3.94 → 4.70), and `--color-error` `#D14343 → #CE3737` (4.26 → 4.63). Every text/background pair in both themes now clears AA; dark theme was already passing and is untouched
- ER diagram table boxes had all of their body padding at the bottom. The height was computed as `headerHeight + rows + padding` while the first row was drawn at exactly `headerHeight`, so the top row sat flush against the header divider and the full 12px collected under the last row. The padding is now split across both ends, leaving the box height unchanged
- The version dropdown no longer looks lighter than the buttons beside it. Its trigger used `--control-bg`, the surface reserved for input fields, even though the trigger is a `<button>` in a toolbar row of `.btn-ghost` buttons and the only `<select>` in that group (`.version-select-native`) is invisible. It now uses the same `--control-bg-quiet` as its neighbours
- Card status borders (added / removed / modified) were hardcoded to colors outside the palette — a green, red and amber that were near, but not equal to, `--color-success` / `--color-error` / `--color-warning`, and that stayed fixed when the theme changed. They now derive from the real tokens and follow light theme properly

### Changed

- The app-shell markup moved out of Kotlin into `web/index.html`. `VisualizerPanel.buildFullHtml()` held the entire HTML document — every panel, toolbar, modal and empty state — as a 429-line raw string, which meant no HTML tooling and a recompile for any markup edit, even though the CSS and all eight scripts were already loaded from resources. `VisualizerPanel` drops from 671 lines to 270 and is now just the panel controller; the Kotlin side substitutes `{{placeholder}}` tokens in a single pass, so injected CSS/JS is never rescanned. Verified the generated document is byte-identical to the previous output apart from one inert trailing newline
- Every button in the web UI is now a member of the `.btn` family. The two bespoke button styles — `.inline-source-btn` (8 call sites) and `.search-action-btn` (3) — each re-implemented a full button from scratch, which the project's own UI rules forbid. They are replaced by two new reusable pieces, a `.btn-xs` size and a `.btn-accent` variant; `.search-action-btn` survives only as a modifier carrying its deliberate accent hover
- 44 raw `rgba()` literals are now `color-mix()` over the design tokens, per the documented tint convention. The remaining literals are drop shadows, inset highlights and fixed gradient stops, which aren't token tints
- Replaced the last hardcoded `font-size` / `font-weight` values on `.btn-sm` and `.risk-badge` with the matching scale tokens, and added the required `line-height: 1` to `.badge` and `.risk-badge`
- Buttons now have a design-system focus ring (`.btn:focus-visible` using `--control-focus-shadow`, the same token the inputs already use). Previously no `.btn` focus rule existed at all, so keyboard focus fell back to the browser default
- Removed dead styling for `.diff-selector select`. Every `<select>` in that container is `.version-select-native`, which is `opacity: 0` at 0×0 — the rules could never render
- Moved the remaining inline styles out of the JS templates into classes (`.empty-state-inline`, `.empty-state-title-success`, `.empty-state-action`); only the per-index `animation-delay` values stay inline, since they're computed

## [1.5.2] - 2026-08-01

### Fixed

- `ALTER TABLE` now resolves its target regardless of identifier case. Unquoted SQL identifiers are case-insensitive, but the parser kept its working schema in a case-sensitive map, so `ALTER TABLE Users …` against a table created as `users` silently did nothing — the statement was dropped and the column simply never appeared in the timeline, diff, or ER diagram. The table keeps whichever spelling it was first declared with
- Schema-qualified table names no longer produce a phantom validation error. `ALTER TABLE public.users` was matched with a pattern that stopped at the dot, so every such statement reported "references non-existent table 'public'". Both the `ALTER TABLE` and `DROP TABLE` checks now use the same qualified-identifier handling as the parser, and compare case-insensitively
- The validator no longer warns about the SQLite table rebuild this plugin generates itself — the `BEGIN`/`COMMIT` pair between `PRAGMA foreign_keys` guards is intentional, so creating a SQLite migration and then validating it no longer flags your own generated output

### Changed

- A migration wrapping its statements in `BEGIN` … `COMMIT` now reports a single transaction warning instead of one per transaction statement

## [1.5.1] - 2026-08-01

### Fixed

- File paths arriving from the web UI are now checked against the project root before any file operation — `openFile`, `saveMigration`, `createMigration`, and `deleteMigration` previously acted on whatever absolute path they were handed. The check canonicalises both paths, so `..` segments and sibling directories that merely share a name prefix (`/project-other` vs `/project`) are rejected; `renumberMigration`'s existing prefix-only check was replaced with the same helper. Export ("Save As") is deliberately exempt — that destination comes from the user's own file dialog
- `escapeJs()` now escapes HTML metacharacters in addition to quotes and backslashes. Its output is embedded in double-quoted `onclick` attributes, so a migration file path containing a double quote could close the attribute early and inject another one — table and column names were never affected, since the parser restricts identifiers to word characters
- `JcefBridge.dispose()` was leaking `renumberMigrationQuery` — 13 `JBCefJSQuery` objects were created but only 12 released, so every tool-window close leaked a query slot

### Changed

- `createMigration` builds the target filename once instead of twice — the second copy re-read the mutable settings state, so a filename-pattern change landing mid-call could return a path that didn't match the file actually written

## [1.5.0] - 2026-06-02

### Added

- Marketing site: "Download latest .zip" CTA in the install section that targets `releases/latest`, so visitors get a one-click path to the prebuilt plugin once a release is published — build-from-source is preserved behind an "Or build from source" disclosure
- Marketing site: `site.webmanifest` linked from the page for installable / PWA-style metadata
- Marketing site: expanded footer with Source · Report an issue · Changelog · License links and a "Built by Jakub Neděla" credit line
- `<link rel="canonical">` on the marketing site, paired with the existing `og:url`, so search engines and link unfurlers resolve the page to a single URL

### Changed

- Marketing site: mobile nav (≤640px) now keeps the section links visible via a horizontally scrollable strip with edge-fade mask, replacing the previous behavior that hid every link except the GitHub button — brand collapses to icon-only on phones to leave room
- Marketing site: inline `<code>` inside body prose (lede, FAQ answers, dialect notes) no longer renders as a chunky blue chip — it's now a soft monospace accent that flows with surrounding text; `<pre>`/`<code>` blocks keep their chip treatment
- Marketing site (`docs/`) is now properly responsive on mobile — fixed the nav cramping at narrow widths (brand text and "Live demo" link previously wrapped to two lines on a 375px viewport), tightened card and section paddings at ≤640px, and added a ≤420px breakpoint for compact phones
- Hero headline is now a single inline sentence — the gradient phrase "the way you think about it." continues from "See your database history" instead of being forced onto its own line by a hard `<br>`, so it reads as one statement and wraps naturally at every width
- Demo control chips (`.seg-chip`) on mobile now meet the 44px WCAG 2.5.5 touch-target recommendation — previously ~30px tall, which was uncomfortable to tap accurately on a phone
- Sticky-nav anchor offset is now derived from a `--nav-h` variable instead of a hardcoded `72px` `scroll-padding-top` — anchored sections (`#demo`, `#dialects`, `#faq`, `#install`) now land with a consistent 8px gap below the nav at every breakpoint, instead of an oversized gap on phones where the nav is shorter
- Lede copy no longer uses "visual" twice in one sentence — "turns it into a navigable timeline" replaces "turns it into a visual timeline"

### Fixed

- Marketing site: FAQ summary marker now hidden cross-browser — added a `summary::marker { content: "" }` rule alongside the existing `::-webkit-details-marker` reset so the native disclosure triangle no longer leaks through on Firefox

### Removed

- Marketing site: "Most loved" tag on the Risk Scoring feature card — was a social-proof claim without data to back it
- Placeholder "Inside the tool window" screenshots section (`#screenshots`) — was showing three generic SVG icons in fake IDE chrome instead of real product captures, which made the section look unfinished; we'll bring it back once there are actual screenshots to ship

## [1.4.0] - 2026-06-01

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
