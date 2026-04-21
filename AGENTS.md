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
├── generator/        MigrationGenerator, SqlDialect (GENERIC/POSTGRESQL/MYSQL)
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
| `app.js` | Main controller, tab routing, validation display |
| `timeline.js` | Interactive version timeline |
| `schema-diff.js` | Side-by-side version comparison |
| `er-diagram.js` | Draggable/zoomable ER diagram |
| `create-migration.js` | Pending migration preview & creation |
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
- `ValidationIssue` — codes: `VERSION_GAP`, `DUPLICATE_VERSION`, `EMPTY_MIGRATION`, `ALTER_TABLE_TARGET_MISSING`, `DROP_TABLE_STATEMENT`, `FOREIGN_KEY_TARGET_MISSING`
- `MigrationRisk` — `level` (LOW/MEDIUM/HIGH), `score`, `headline`, `items`
- `PendingMigrationSuggestion` — `hasPendingChanges`, `generatedSql`, `suggestedVersion`, `suggestedName`

## Risk Analyser Rules

HIGH: table drop, column drop, required column added without default, type narrowing  
MEDIUM: NULL tightening, type change (non-narrowing), PK/FK changes

## Validator Issue Codes

`VERSION_GAP`, `DUPLICATE_VERSION`, `EMPTY_MIGRATION`, `ALTER_TABLE_TARGET_MISSING`, `TRANSACTION_STATEMENT`, `DROP_TABLE_STATEMENT`, `FOREIGN_KEY_TARGET_MISSING`

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
`openFile`, `requestRefresh`, `generateMigration`, `saveMigration`, `saveFile`, `createMigration`, `browseDirectory`, `deleteMigration`, `saveErLayout`, `dismissPendingMigration`, `openRelatedSchemaSource`, `saveDiffSelection`

**Adding a new JS → Kotlin call** requires changes in three places:

1. Declare a `JBCefJSQuery` field at the top of `JcefBridge`:
   ```kotlin
   private val myActionQuery = JBCefJSQuery.create(browser)
   ```
2. Register its handler in `setupQueryHandlers()`:
   ```kotlin
   myActionQuery.addHandler { payload ->
       // handle payload (JSON string)
       JBCefJSQuery.Response("ok")  // or Response(null, 0, "error msg")
   }
   ```
3. Expose it to JS in `buildBridgeFunctions()` — add to the `inject` block and to the `window.__bridge` object:
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

## Do Not

- Add a JS framework to the web frontend — stay vanilla JS.
- Edit `build/*/plugin.xml` — it is generated; the source is `src/main/resources/META-INF/plugin.xml`.
- Call IntelliJ file/VFS APIs from a background thread without a `ReadAction` or `WriteAction` wrapper.
- Escape JS string literals with JSON encoding — manually escape `\`, `'`, `\n`, `\r` before injecting into single-quoted JS strings (see `JcefBridge`).

## Platform Target

`platformType = IC` (IntelliJ IDEA Community), `platformVersion = 2024.1`. Avoid IntelliJ APIs that were added after 2024.1 — check compatibility before using newer platform classes.

## Conventions

- No comments unless the WHY is non-obvious
- Prefer editing existing files over creating new ones
- All inter-layer communication is JSON via `JcefBridge`
- Threading: file watchers run on background threads; UI updates dispatched to EDT

## Versioning

After every commit+push, run `./bump-version.sh <version>` and update CHANGELOG:
- New feature → minor bump (1.0.0 → 1.1.0)
- Bug fix → patch bump (1.0.0 → 1.0.1)
- Breaking change → major bump (1.0.0 → 2.0.0)
