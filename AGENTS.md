# SQL Migration Visualizer — Agent Context

IntelliJ IDEA plugin (Kotlin 2.0.21, Java 17) that visualizes SQL migration history as an interactive timeline. Uses JCEF for a vanilla-JS web UI embedded in the IDE.

## Build & Test

```bash
./gradlew test          # run all unit tests
./gradlew runIde        # launch IDE sandbox with plugin
./gradlew buildPlugin   # produce distributable ZIP
./gradlew verifyPlugin  # binary compatibility check
```

Tests use Kotlin Test (JUnit Platform). All tests are pure unit tests — no IDE runtime needed.

Typical test shape — no base class, inline factory helpers, backtick names:

```kotlin
class FooTest {
    private val subject = FooClass()

    @Test
    fun `descriptive name in backticks`() {
        val result = subject.method(input)
        assertEquals(expected, result)
    }
}
```

Assertions: `assertEquals`, `assertTrue`, `assertNotNull`, `assertContains` from `kotlin.test`.

## Package Map

```
io.github.jakub.sqlmigrationvisualizer/
├── MigrationVisualizerToolWindowFactory  # entry point, creates tool window
├── actions/          RefreshMigrationsAction, ValidateMigrationsAction
├── analyzer/         SchemaChangeRiskAnalyzer   — scores HIGH/MEDIUM/LOW risk
├── generator/        MigrationGenerator, SqlDialect (GENERIC/POSTGRESQL/MYSQL/SQLITE)
├── model/            all @Serializable data classes (JSON bridge)
├── parser/           SqlParser (DDL → SchemaVersion list), MigrationScanner
├── services/         ProjectSchemaSnapshotService, VisualizerPanelManager
├── settings/         VisualizerSettings, VisualizerConfigurable
├── startup/          SchemaChangePromptStartupActivity
├── ui/               VisualizerPanel (JCEF host), JcefBridge (JS↔Kotlin)
├── util/             MigrationDirectoryDetector, MigrationFileNaming
├── validator/        MigrationValidator
└── watcher/          SchemaChangePromptService, SchemaChangePromptStateCalculator
```

## Web Frontend

`src/main/resources/web/` — vanilla JS, no framework:

| File | Purpose |
|------|---------|
| `index.html` | App shell markup — panels, toolbars, modals. `{{placeholder}}` tokens are substituted by `VisualizerPanel.buildFullHtml()` |
| `app.js` | Main controller, tab routing, validation display |
| `timeline.js` | Interactive version timeline |
| `schema-diff.js` | Side-by-side version comparison |
| `er-diagram.js` | ER diagram — click-drag to pan, zoom via +/-/fit buttons |
| `create-migration.js` | Create/edit migration modal |
| `search.js` | Full-text search across schema |
| `sql-highlight.js` | Syntax highlighting |
| `export.js` | Export functionality |
| `styles.css` | All styling |

## Key Data Flow

1. `MigrationScanner` finds `.sql`/`.sqm` files matching versioned patterns
2. `SqlParser` applies DDL statements sequentially → `List<SchemaVersion>`
3. Each `SchemaVersion` holds `Map<String, TableSchema>` + `ChangesSummary` + `MigrationRisk`
4. `JcefBridge` serializes to JSON and passes to JS frontend via JCEF
5. JS renders timeline/diff/ER views; user actions call back into Kotlin via `JcefBridge`

## Migration File Patterns Recognised

- `1.sql`, `2.sqm`
- `12_add_users.sql`
- `V3__create_orders.sql` (Flyway)

Auto-detected directories: `db/migrations`, `migrations`, `src/main/sqldelight/**`, `src/main/resources/db/migrations`, etc.

## Model Highlights

All models are `@Serializable` (kotlinx-serialization-json 1.7.3):

- `SchemaVersion` — full DB state at one version; has `tables`, `migrationFile`, `changesSummary`, `risk`
- `TableSchema` — `name`, `columns: List<ColumnDef>`, `primaryKey`, `foreignKeys`
- `ColumnDef` — `name`, `type`, `nullable`, `defaultValue`, `isPrimaryKey`
- `ValidationIssue` — codes: `NO_MIGRATIONS`, `VERSION_GAP`, `DUPLICATE_VERSION`, `EMPTY_MIGRATION`, `ALTER_TABLE_TARGET_MISSING`, `TRANSACTION_STATEMENT`, `DROP_TABLE_STATEMENT`, `FOREIGN_KEY_TARGET_MISSING`
- `MigrationRisk` — `level` (LOW/MEDIUM/HIGH), `score`, `headline`, `items`
- `PendingMigrationSuggestion` — `hasPendingChanges`, `generatedSql`, `suggestedVersion`, `suggestedName`

## Risk Analyser Rules

HIGH: table drop, column drop, required column added without default, type narrowing
MEDIUM: NULL tightening, type change (non-narrowing), PK/FK changes

## Validator Issue Codes

`NO_MIGRATIONS`, `VERSION_GAP`, `DUPLICATE_VERSION`, `EMPTY_MIGRATION`, `ALTER_TABLE_TARGET_MISSING`, `TRANSACTION_STATEMENT`, `DROP_TABLE_STATEMENT`, `FOREIGN_KEY_TARGET_MISSING`

`ALTER_TABLE_TARGET_MISSING` and `DROP_TABLE_STATEMENT` resolve their target using the same qualified-identifier pattern as `SqlParser`, matched case-insensitively — a bare `(\w+)` stops at the dot and reports the schema of `public.users` as a missing table. `TRANSACTION_STATEMENT` fires at most once per file, and is suppressed entirely when the migration contains a `PRAGMA foreign_keys` guard, since that is the SQLite rebuild `MigrationGenerator` emits itself.

## Plugin Entry Points (`plugin.xml`)

- Tool window: "SQL Migrations" (bottom panel)
- Post-startup activity: schema change detection
- Settings: project-level under Tools > SQL Migration Visualizer
- Notification group: "SQL Migration Visualizer"

## VisualizerSettings Fields

`VisualizerSettings.State` (stored in `SqlMigrationVisualizer.xml`); access via `VisualizerSettings.getInstance(project)`:

| Field | Type | Default |
|-------|------|---------|
| `showBaselineInTimeline` | Boolean | true |
| `autoExpandTableCards` | Boolean | true |
| `defaultTab` | String | `"timeline"` |
| `preferredSqlDialect` | String | `"generic"` |
| `erShowGrid` | Boolean | true |
| `erLayoutColumns` | Int | 0 |
| `erTablePositions` | Map | empty |
| `diffShowUnchangedColumns` | Boolean | true |
| `rememberDiffSelections` | Boolean | true |
| `lastDiffFromVersion` | Int | 0 |
| `lastDiffToVersion` | Int | 0 |
| `searchResultLimit` | Int | 20 |
| `validateOnRefresh` | Boolean | true |
| `suggestPendingMigrationOnSave` | Boolean | true |
| `confirmBeforeDeleteMigration` | Boolean | true |
| `autoOpenCreatedMigration` | Boolean | true |
| `defaultMigrationDirectory` | String | `""` |
| `additionalMigrationDirectories` | String | `""` |
| `migrationFileNamePattern` | String | `"{version}"` |

## JcefBridge Communication

**Kotlin → JS** (push methods):
- `pushSchemaData(versions)` → `window.__onSchemaData`
- `pushValidationData(result)` → `window.__onValidationData`
- `pushSettings(state)` → `window.__onSettingsChanged`
- `pushPendingMigrationSuggestion(suggestion)` → `window.__onPendingMigrationData`
- `pushTheme(isDark)`, `pushMigrationDirectory()`

**JS → Kotlin** (query handlers, all registered as `window.__bridge.*`):
`openFile`, `requestRefresh`, `generateMigration`, `saveMigration`, `saveFile`, `createMigration`, `browseDirectory`, `deleteMigration`, `renumberMigration`, `saveErLayout`, `dismissPendingMigration`, `openRelatedSchemaSource`, `saveDiffSelection`

**Adding a new JS → Kotlin call** requires changes in three places:

1. Declare a `JBCefJSQuery` field at the top of `JcefBridge`:
   ```kotlin
   private val myActionQuery = JBCefJSQuery.create(browser)
   ```
2. Register its handler in `setupQueryHandlers()`:
   ```kotlin
   myActionQuery.addHandler { payload ->
       JBCefJSQuery.Response("ok")
   }
   ```
3. Expose it to JS in `buildBridgeFunctions()`:
   ```kotlin
   val myActionJs = myActionQuery.inject("json")
   // inside the return """ ... """ block:
   // myAction: function(json) { $myActionJs },
   ```

## Threading Rules

- **File writes** must run inside `invokeLater { WriteCommandAction.runWriteCommandAction(project) { … } }` — never call VirtualFile write methods directly from JCEF callbacks.
- **File reads** use `ReadAction.nonBlocking { … }.finishOnUiThread(ModalityState.any()).expireWith(this)` — `ModalityState.defaultModalityState()` deadlocks when a dialog is open.
- **`getSnapshotUnderReadAction()`** must be called from within a `ReadAction.compute {}` — not from the EDT directly.
- **Async services** (`SchemaChangePromptService`, `VisualizerPanel` alarm) run on `POOLED_THREAD`; always guard callbacks with `if (project.isDisposed) return`.
- **Do not nest** `WriteCommandAction` inside another write action.

## Security

### Rules — always follow these

- **Never inject unescaped user or plugin-supplied strings into `innerHTML`** — always pass through `escapeHtml()` first. Use `textContent` for plain text.
- **Never use `eval`, `new Function(string)`, or `setTimeout(string)`.** All JS is static and bundled; no dynamic execution.
- **Never trust JS → Kotlin payloads without parsing.** All `window.__bridge.*` handlers receive raw strings; parse as JSON and validate fields before use.
- **Never write sensitive project data outside the project VFS.** The bridge only passes schema structure and file paths — nothing user-credential-related.
- **Validate all file paths received from JS** — check the path is within the project before any file operation.

### Checks — run mentally before every commit

- Does any new code inject a string into `innerHTML` without `escapeHtml`? → Fix it.
- Does any new bridge handler use a received file path without validating it's within the project? → Add a check.
- Does any new code add a `console.log` that could leak file paths or schema content? → Remove it.
- Does any new Kotlin code touch the file system from a JCEF callback without a `WriteCommandAction`? → Fix the threading.

### Existing security measures (do not remove or weaken)

- `escapeHtml()` in `create-migration.js` — used on all user-supplied strings before `innerHTML` assignment
- All JS→Kotlin payloads parsed as JSON in `JcefBridge` handlers before field access
- File write operations wrapped in `WriteCommandAction.runWriteCommandAction` — prevents concurrent write corruption
- `if (project.isDisposed) return` guards on all async callbacks — prevents use-after-free on project close

## UI Design System

All web UI lives in `src/main/resources/web/styles.css`. Follow the existing design language when adding new components.

### CSS Variables (key tokens)

| Variable | Usage |
|----------|-------|
| `--accent-primary` | Blue accent (`#6aa6ff`) — borders, focus rings, active states |
| `--bg-primary` / `--bg-secondary` / `--bg-tertiary` | Surface hierarchy |
| `--text-primary` / `--text-secondary` / `--text-muted` | Text hierarchy |
| `--border-default` | Neutral borders |
| `--radius-sm` / `--radius-md` / `--radius-lg` | Border radii |
| `--space-sm` / `--space-md` / `--space-lg` | Spacing scale |
| `--font-mono` | Monospace stack for SQL and code |
| `--transition-fast` | `0.15s ease` — use on hover/focus transitions |

### Rules

- **No inline styles on new components** — use CSS classes and variables only.
- **No JS framework** — all UI is vanilla JS with DOM manipulation.
- **Pills / chips / tags / badges** (status pills, version chips, change chips, risk badges, etc.) must use `display: inline-flex; align-items: center; justify-content: center; line-height: 1`. Plain `<span>` with `padding` + `min-height` leaves text baseline-aligned and looks off-center, especially when `letter-spacing` or `text-transform: uppercase` is in play. Always centered on both axes.
- **Use status colors consistently**: `--color-success` for added/created, `--color-error` for removed/dropped, `--color-warning` for modified/at-risk, `--accent-primary` for informational/baseline. Don't introduce new green/red/yellow tokens — extend the existing ones via `color-mix(in srgb, … X%, transparent)` for tints.
- **Never put `color: white` on a `--color-*` status background.** In dark theme those tokens are light pastels meant for *foreground* use, so white on them lands around 1.6–2.0:1. Use `var(--text-inverse)` — it flips to near-black in dark theme and white in light theme, keeping both above the WCAG AA 4.5:1 floor. Literal white is only correct on genuinely dark fills like `--accent-gradient` or the `.btn-danger` gradient.
- **Canvas (ER diagram) draw order**: stroke a shape's border *last*. A canvas stroke straddles its path, so any fill painted afterwards (e.g. the table header) covers the inner half and the border silently renders at half width there.
- **Hide zero-count summary chips** instead of showing `0 errors · 0 warnings · 0 info` rows — render only non-zero pills. If everything's zero, skip the summary container entirely.
- **Status badges**: `.added` (green) and `.baseline` (blue) are semantically distinct — don't reuse `--color-success` for both.
- **Modal sizing**: `.modal-body` is `flex: 0 1 auto` so dialogs shrink to content; the body can scroll when it would exceed the modal's `max-height: 80vh`. Don't restore `flex: 1` — short dialogs (e.g. Review Pending Migration with a short snippet) will end up with a tall empty area below the content.
- **SQL editor**: textarea is in normal flow (drives height); syntax-highlight `<pre>` is `position: absolute; inset: 0` behind it. Mirror divs (`_heightMirror`, `_caretMirror`) are persistent, created once on first use. The autosize routine must run *after* `display: flex` is applied to the modal — measuring while the textarea is hidden produces `offsetWidth = 0` and an inflated height.
- **Modal forms**: use `form-group` / `form-label` / `form-input` classes. Version inputs use `version-input-group` + `version-stepper` layout. The Name field is hidden in edit mode (the filename can't be changed there).
- **Modal close paths** route through `requestClose()` (Create Migration) for dirty-state confirmation; only the success callbacks (`__onMigrationCreated`, `__onMigrationSaved`) call `closeModal()` directly. Escape closes the topmost modal; the existing autocomplete `Escape` handler `preventDefault`s, and the outer modal handler skips if `event.defaultPrevented` so closing autocomplete doesn't also close the dialog.
- **Buttons** must use the `.btn` base class plus a variant (`.btn-primary` for the single primary action per surface, `.btn-ghost` for secondary actions, `.btn-danger` for destructive). Don't add one-off button styles like `#some-id { color: var(--accent-primary) }` — every button on a row should look like a member of the same family.
- **Disabled buttons** rely on the global `.btn:disabled` / `.btn[disabled]` rule (opacity 0.55 + saturation drop + `pointer-events: none`). Use this for in-flight async actions; the existing renderer typically replaces the button anyway, so don't manually re-enable.
- **ER diagram**: zoom is button-only (`+`, `−`, Fit). Click-drag the canvas to pan. Table dragging and wheel zoom are intentionally disabled. The `#panel-er-diagram` panel has `overflow: hidden` so the canvas (sized from its own `getBoundingClientRect`) can't trigger panel scrollbars.
- **Responsive breakpoints** are handled via `#app.compact-width` (≤860px), `#app.very-compact` (≤620px), `#app.short-height` (≤780px), `#app.ultra-compact` (≤460px), and `#app.very-short` (≤620px height) class toggles set by `updateResponsiveLayout()` in `app.js`.

## Platform Target

`platformType = IC` (IntelliJ IDEA Community), `platformVersion = 2024.1`. Avoid IntelliJ APIs added after 2024.1 — check compatibility before using newer platform classes.

## Do Not

- Add a JS framework to the web frontend — stay vanilla JS.
- Edit `build/*/plugin.xml` — it is generated; the source is `src/main/resources/META-INF/plugin.xml`.
- Put app-shell markup back into `VisualizerPanel.kt` — it lives in `web/index.html`. The Kotlin side only substitutes `{{placeholder}}` tokens (one per inlined resource) in a single pass, so injected CSS/JS is never rescanned for placeholders.
- Call IntelliJ file/VFS APIs from a background thread without a `ReadAction` or `WriteAction` wrapper.
- Escape JS string literals with JSON encoding — manually escape `\`, `'`, `\n`, `\r` before injecting into single-quoted JS strings (see `JcefBridge`).
- Use `project.baseDir` (deprecated) — use `project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }`.

## Documentation

- Update `CHANGELOG.md` for every commit — add an entry under the appropriate version.
- Update `README.md` when a change is user-facing: new features, changed behaviour, new keyboard shortcuts, new supported file patterns.
- Update `INSTALL.md` when installation steps, requirements, or supported IntelliJ versions change.
- Internal refactors and non-visible bug fixes do not require README or INSTALL updates.

Do not add `Co-Authored-By:` trailers to git commits.

## Before Every Commit

1. **Bump `pluginVersion`** in `gradle.properties` using these rules:

   | Change | Bump | Example |
   |--------|------|---------|
   | New user-facing feature | **minor** `x.+1.0` | `1.1.x → 1.2.0` |
   | Bug fix, style tweak, refactor | **patch** `x.x.+1` | `1.1.x → 1.1.x+1` |
   | Breaking change or major rewrite | **major** `+1.0.0` | `1.x.x → 2.0.0` |

2. **Update `CHANGELOG.md`** — add an entry under the new version with a short summary of what changed.
3. **Update `README.md`** if the change is user-facing.

4. **Suggest a GitHub Release** after every commit if any of the following apply — say "this looks like a good point to publish a GitHub Release":
   - A security fix was made
   - A user-facing feature was added
   - A bug affecting core functionality was fixed (timeline not loading, migrations not creating, validation not running)

   Do NOT suggest a release for: docs-only changes, internal refactors, style tweaks the user won't notice.
