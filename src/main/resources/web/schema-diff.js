/**
 * SQL Migration Visualizer — Schema Diff Module
 *
 * Renders a side-by-side diff view comparing schema between any two versions.
 * Highlights added, removed, and modified tables and columns with change details.
 */
(function() {
    'use strict';

    const SchemaDiffModule = {
        render: function(state) {
            const versions = state.schemaVersions;
            const content = document.getElementById('diff-content');
            const summary = document.getElementById('diff-summary');

            if (!versions || versions.length < 2) {
                if (summary) summary.innerHTML = '';
                content.innerHTML = `
                    <div class="empty-state" style="height: auto; padding: 40px">
                        <h3>Need at least 2 versions</h3>
                        <p>Add migration files to compare schema versions.</p>
                    </div>
                `;
                return;
            }

            const fromVersion = parseInt(document.getElementById('diff-from').value, 10);
            const toVersion = parseInt(document.getElementById('diff-to').value, 10);

            const fromSchema = versions.find(v => v.version === fromVersion);
            const toSchema = versions.find(v => v.version === toVersion);

            if (!fromSchema || !toSchema) {
                if (summary) summary.innerHTML = '';
                content.innerHTML = '<div class="empty-state"><p>Select valid versions to compare.</p></div>';
                return;
            }

            const diff = this.computeDiff(fromSchema, toSchema);
            if (summary) {
                summary.innerHTML = this.renderSummary(diff, fromSchema, toSchema);
            }
            content.innerHTML = this.renderDiff(diff);

            const genBtn = document.getElementById('btn-generate-migration');
            if (genBtn) {
                genBtn.style.display = diff.length > 0 ? 'inline-flex' : 'none';
                genBtn.onclick = function() {
                    SchemaDiffModule.requestGeneration(fromVersion, toVersion);
                };
            }
        },

        computeDiff: function(fromSchema, toSchema) {
            const fromTables = fromSchema.tables;
            const toTables = toSchema.tables;
            const allTableNames = new Set([].concat(Object.keys(fromTables), Object.keys(toTables)));
            const result = [];

            for (const name of Array.from(allTableNames).sort()) {
                const fromTable = fromTables[name];
                const toTable = toTables[name];

                if (!fromTable && toTable) {
                    result.push({
                        name: name,
                        status: 'new',
                        fromTable: null,
                        toTable: toTable,
                        columns: toTable.columns.map(col => ({
                            name: col.name,
                            status: 'added',
                            before: null,
                            after: col,
                            changeDetails: []
                        })),
                        notes: [],
                        counts: {
                            added: toTable.columns.length,
                            removed: 0,
                            modified: 0
                        }
                    });
                    continue;
                }

                if (fromTable && !toTable) {
                    result.push({
                        name: name,
                        status: 'dropped',
                        fromTable: fromTable,
                        toTable: null,
                        columns: fromTable.columns.map(col => ({
                            name: col.name,
                            status: 'removed',
                            before: col,
                            after: null,
                            changeDetails: []
                        })),
                        notes: [],
                        counts: {
                            added: 0,
                            removed: fromTable.columns.length,
                            modified: 0
                        }
                    });
                    continue;
                }

                if (!fromTable || !toTable) continue;

                const fromColumns = {};
                const toColumns = {};
                fromTable.columns.forEach(function(col) { fromColumns[col.name] = col; });
                toTable.columns.forEach(function(col) { toColumns[col.name] = col; });

                const allColumnNames = new Set([].concat(Object.keys(fromColumns), Object.keys(toColumns)));
                const columns = [];
                const counts = { added: 0, removed: 0, modified: 0 };

                for (const columnName of Array.from(allColumnNames).sort()) {
                    const before = fromColumns[columnName] || null;
                    const after = toColumns[columnName] || null;

                    if (!before && after) {
                        counts.added += 1;
                        columns.push({
                            name: columnName,
                            status: 'added',
                            before: null,
                            after: after,
                            changeDetails: []
                        });
                        continue;
                    }

                    if (before && !after) {
                        counts.removed += 1;
                        columns.push({
                            name: columnName,
                            status: 'removed',
                            before: before,
                            after: null,
                            changeDetails: []
                        });
                        continue;
                    }

                    const changeDetails = this.describeColumnChanges(before, after);
                    if (changeDetails.length > 0) {
                        counts.modified += 1;
                    }

                    columns.push({
                        name: columnName,
                        status: changeDetails.length > 0 ? 'modified' : 'unchanged',
                        before: before,
                        after: after,
                        changeDetails: changeDetails
                    });
                }

                const notes = this.describeTableChanges(fromTable, toTable);
                const hasChanges = counts.added > 0 || counts.removed > 0 || counts.modified > 0 || notes.length > 0;
                if (!hasChanges) {
                    continue;
                }

                result.push({
                    name: name,
                    status: 'changed',
                    fromTable: fromTable,
                    toTable: toTable,
                    columns: columns,
                    notes: notes,
                    counts: counts
                });
            }

            return result;
        },

        describeColumnChanges: function(before, after) {
            const changes = [];
            if (!before || !after) return changes;

            if (before.type !== after.type) {
                changes.push({
                    label: 'type',
                    from: before.type,
                    to: after.type
                });
            }

            if (!!before.nullable !== !!after.nullable) {
                changes.push({
                    label: 'nullability',
                    from: before.nullable ? 'NULL' : 'NOT NULL',
                    to: after.nullable ? 'NULL' : 'NOT NULL'
                });
            }

            if ((before.defaultValue || null) !== (after.defaultValue || null)) {
                changes.push({
                    label: 'default',
                    from: before.defaultValue || 'none',
                    to: after.defaultValue || 'none'
                });
            }

            if (!!before.isPrimaryKey !== !!after.isPrimaryKey) {
                changes.push({
                    label: 'primary key',
                    from: before.isPrimaryKey ? 'yes' : 'no',
                    to: after.isPrimaryKey ? 'yes' : 'no'
                });
            }

            return changes;
        },

        describeTableChanges: function(fromTable, toTable) {
            const notes = [];
            const fromPrimaryKey = JSON.stringify((fromTable.primaryKey || []).slice().sort());
            const toPrimaryKey = JSON.stringify((toTable.primaryKey || []).slice().sort());
            if (fromPrimaryKey !== toPrimaryKey) {
                notes.push('Primary key definition changed');
            }

            const fkSignature = function(table) {
                return JSON.stringify((table.foreignKeys || []).map(function(fk) {
                    return {
                        columns: (fk.columns || []).slice().sort(),
                        referencedTable: fk.referencedTable,
                        referencedColumns: (fk.referencedColumns || []).slice().sort()
                    };
                }).sort(function(a, b) {
                    return JSON.stringify(a).localeCompare(JSON.stringify(b));
                }));
            };

            if (fkSignature(fromTable) !== fkSignature(toTable)) {
                notes.push('Foreign key relationships changed');
            }

            return notes;
        },

        renderSummary: function(diff, fromSchema, toSchema) {
            if (diff.length === 0) {
                return `
                    <div class="diff-summary-card">
                        <div class="diff-summary-title">No schema changes</div>
                        <div class="diff-summary-subtitle">Versions ${fromSchema.version} and ${toSchema.version} expose identical schemas.</div>
                    </div>
                `;
            }

            const totals = diff.reduce(function(acc, tableDiff) {
                acc.tables += 1;
                acc.added += tableDiff.counts.added;
                acc.removed += tableDiff.counts.removed;
                acc.modified += tableDiff.counts.modified;
                acc.notes += tableDiff.notes.length;
                return acc;
            }, { tables: 0, added: 0, removed: 0, modified: 0, notes: 0 });

            return `
                <div class="diff-summary-card">
                    <div>
                        <div class="diff-summary-title">Version ${fromSchema.version} -> Version ${toSchema.version}</div>
                        <div class="diff-summary-subtitle">Review table additions, removals, and property-level column changes before generating a migration.</div>
                    </div>
                    <div class="diff-summary-pills">
                        <span class="diff-summary-pill"><strong>${totals.tables}</strong> tables changed</span>
                        <span class="diff-summary-pill added"><strong>${totals.added}</strong> columns added</span>
                        <span class="diff-summary-pill removed"><strong>${totals.removed}</strong> columns removed</span>
                        <span class="diff-summary-pill modified"><strong>${totals.modified}</strong> columns modified</span>
                        <button type="button" class="btn btn-ghost btn-sm diff-summary-copy-btn" onclick="window.AppUi && window.AppUi.copyText('Schema diff summary: Version ${fromSchema.version} to Version ${toSchema.version}. ${totals.tables} tables changed, ${totals.added} columns added, ${totals.removed} columns removed, ${totals.modified} columns modified.', 'Diff summary copied.')">Copy Summary</button>
                    </div>
                </div>
            `;
        },

        renderDiff: function(diff) {
            if (diff.length === 0) {
                return `
                    <div class="empty-state" style="height: auto; padding: 40px">
                        <h3>No differences</h3>
                        <p>The two selected versions have identical schemas.</p>
                    </div>
                `;
            }

            const showUnchanged = !window.AppHelpers || !window.AppHelpers.getState().settings || window.AppHelpers.getState().settings.diffShowUnchangedColumns !== false;

            return diff.map(function(tableDiff, index) {
                const filteredColumns = showUnchanged
                    ? tableDiff.columns
                    : tableDiff.columns.filter(function(column) { return column.status !== 'unchanged'; });

                const linesHtml = filteredColumns.map(function(column) {
                    return SchemaDiffModule.renderColumnLine(column, tableDiff.name);
                }).join('');

                const notesHtml = tableDiff.notes.map(function(note) {
                    return '<div class="diff-note">' + escapeHtml(note) + '</div>';
                }).join('');

                const summaryPills = [];
                if (tableDiff.counts.added > 0) {
                    summaryPills.push('<span class="diff-mini-pill added">+' + tableDiff.counts.added + ' cols</span>');
                }
                if (tableDiff.counts.removed > 0) {
                    summaryPills.push('<span class="diff-mini-pill removed">-' + tableDiff.counts.removed + ' cols</span>');
                }
                if (tableDiff.counts.modified > 0) {
                    summaryPills.push('<span class="diff-mini-pill modified">~' + tableDiff.counts.modified + ' cols</span>');
                }

                return `
                    <section class="diff-table-section stagger-item" data-table-name="${escapeHtml(tableDiff.name)}" style="animation-delay: ${index * 0.05}s">
                        <div class="diff-table-header">
                            <span>${escapeHtml(tableDiff.name)}</span>
                            <span class="status-tag ${tableDiff.status}">${tableDiff.status}</span>
                            <button type="button" class="inline-source-btn diff-source-btn" title="Focus table history" onclick="window.AppActions && window.AppActions.showTableHistory('${escapeJs(tableDiff.name)}', ${document.getElementById('diff-to').value})">History</button>
                            <button type="button" class="inline-source-btn diff-source-btn" title="Open related schema file" onclick="window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(tableDiff.name)}'}))">Open SQL</button>
                            <div class="diff-table-pills">${summaryPills.join('')}</div>
                        </div>
                        <div class="diff-table-body">
                            ${notesHtml}
                            ${linesHtml}
                        </div>
                    </section>
                `;
            }).join('');
        },

        renderColumnLine: function(column, tableName) {
            const prefix = column.status === 'added'
                ? '+'
                : column.status === 'removed'
                    ? '−'
                    : column.status === 'modified'
                        ? '~'
                        : ' ';
            const lineClass = column.status;
            const columnDef = column.after || column.before;
            const main = this.renderColumnDefinition(columnDef);
            const changeMeta = column.changeDetails.map(function(change) {
                return '<span class="diff-change-chip">' +
                    escapeHtml(change.label) + ': ' +
                    escapeHtml(change.from) + ' -> ' +
                    escapeHtml(change.to) +
                    '</span>';
            }).join('');

            return `
                <div class="diff-line ${lineClass}" data-column-name="${escapeHtml(column.name)}">
                    <span class="diff-prefix">${prefix}</span>
                    <div class="diff-line-content">
                        <div class="diff-line-main">${main}<button type="button" class="inline-source-btn diff-source-btn" title="Open related schema file" onclick="event.stopPropagation(); window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(tableName)}',columnName:'${escapeJs(column.name)}'}))">SQL</button></div>
                        ${changeMeta ? `<div class="diff-line-meta">${changeMeta}</div>` : ''}
                    </div>
                </div>
            `;
        },

        renderColumnDefinition: function(column) {
            if (!column) return '';

            const parts = [
                '<span class="diff-column-name">' + escapeHtml(column.name) + '</span>',
                '<span class="diff-column-type">' + escapeHtml(column.type) + '</span>'
            ];

            if (column.isPrimaryKey) {
                parts.push('<span class="diff-column-attr">PRIMARY KEY</span>');
            }
            if (!column.nullable) {
                parts.push('<span class="diff-column-attr">NOT NULL</span>');
            }
            if (column.defaultValue) {
                parts.push('<span class="diff-column-attr">DEFAULT ' + escapeHtml(column.defaultValue) + '</span>');
            }

            return parts.join('');
        },

        requestGeneration: function(fromVersion, toVersion) {
            if (!window.__bridge || !window.__bridge.generateMigration) return;
            window.__bridge.generateMigration(JSON.stringify({
                fromVersion: fromVersion,
                toVersion: toVersion
            }));
            this._pendingFrom = fromVersion;
            this._pendingTo = toVersion;
        }
    };

    window.__onMigrationGenerated = function(sql) {
        var modal = document.getElementById('migration-modal');
        var codeEl = document.getElementById('migration-code');
        var highlighted = window.SqlHighlighter ? window.SqlHighlighter.highlight(sql) : escapeHtml(sql);
        codeEl.innerHTML = highlighted;
        modal.style.display = 'flex';
        modal._rawSql = sql;
    };

    window.SchemaDiffModule = SchemaDiffModule;
})();
