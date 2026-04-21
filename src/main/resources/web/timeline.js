/**
 * SQL Migration Visualizer — Timeline Module
 *
 * Renders an interactive horizontal timeline showing each schema version
 * with animated connectors and click-to-select.
 */
(function() {
    'use strict';

    const TimelineModule = {
        render: function(state) {
            const versions = state.schemaVersions;
            const track = document.getElementById('timeline-track');
            const empty = document.getElementById('timeline-empty');
            const detail = document.getElementById('schema-detail');
            const timelineContainer = document.getElementById('timeline-container');
            const timelinePanel = document.getElementById('panel-timeline');
            const preview = document.getElementById('timeline-hover-preview');
            const migrationVersions = versions ? versions.filter(function(v) { return v.migrationFile != null; }) : [];

            if (!versions || versions.length === 0 || migrationVersions.length === 0) {
                timelineContainer.classList.add('is-empty');
                timelinePanel.classList.add('is-empty');
                track.style.display = 'none';
                preview.style.display = 'none';
                empty.style.display = 'flex';
                detail.style.display = 'none';
                return;
            }

            var showBaseline = !state.settings || state.settings.showBaselineInTimeline !== false;
            var displayVersions = showBaseline ? versions : versions.filter(function(v) { return v.migrationFile != null; });

            if (displayVersions.length === 0) {
                timelineContainer.classList.add('is-empty');
                timelinePanel.classList.add('is-empty');
                track.style.display = 'none';
                preview.style.display = 'none';
                empty.style.display = 'flex';
                detail.style.display = 'none';
                return;
            }

            timelineContainer.classList.remove('is-empty');
            timelinePanel.classList.remove('is-empty');
            empty.style.display = 'none';
            track.style.display = 'flex';
            preview.style.display = 'none';
            preview.innerHTML = '';
            detail.style.display = 'block';

            if (state.selectedVersion < 0 || state.selectedVersion >= displayVersions.length) {
                state.selectedVersion = displayVersions.length - 1;
            }

            track.innerHTML = '';
            displayVersions.forEach((version, index) => {
                const node = document.createElement('div');
                node.className = 'timeline-node' + (index === state.selectedVersion ? ' selected' : '');
                node.dataset.index = index;

                let dotClass = 'timeline-dot';
                const changes = version.changesSummary;
                if (changes) {
                    if (changes.tablesAdded.length > 0) dotClass += ' has-additions';
                    else if (changes.tablesRemoved.length > 0) dotClass += ' has-removals';
                    else if (changes.tablesModified.length > 0) dotClass += ' has-modifications';
                }

                node.innerHTML = `
                    <div class="${dotClass}">
                        ${version.version}
                    </div>
                    <div class="timeline-label">
                        <div class="timeline-version">${version.migrationFile ? version.migrationFile.fileName : 'Baseline'}</div>
                    </div>
                `;

                node.addEventListener('click', () => {
                    state.selectedVersion = index;
                    this.render(state);
                });
                track.appendChild(node);

                if (index < displayVersions.length - 1) {
                    const connector = document.createElement('div');
                    connector.className = 'timeline-connector';
                    track.appendChild(connector);

                    setTimeout(() => {
                        connector.classList.add('animated');
                    }, 100 + index * 100);
                }
            });

            this.renderDetail(displayVersions[state.selectedVersion], state);

            const selectedNode = track.querySelector('.timeline-node.selected');
            if (selectedNode) {
                selectedNode.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
            }
        },

        hidePreview: function(previewEl) {
            if (!previewEl) return;
            previewEl.style.display = 'none';
            previewEl.innerHTML = '';
        },

        renderDetail: function(version, state) {
            if (!version) return;

            const title = document.getElementById('schema-detail-title');
            const subtitle = document.getElementById('schema-detail-subtitle');
            const body = document.getElementById('schema-tables-grid');
            const gotoBtn = document.getElementById('btn-goto-source');
            const deleteBtn = document.getElementById('btn-delete-migration');
            const changesSummaryEl = document.getElementById('changes-summary');
            const changesList = document.getElementById('changes-list');
            const historyPanel = document.getElementById('table-history-panel');
            const focus = state && state.ui ? state.ui.timelineFocus : null;
            const focusedTableName = focus && focus.tableName ? focus.tableName : null;
            const tableExistsAnywhere = focusedTableName
                ? this.tableExistsInAnyVersion(focusedTableName, state.schemaVersions)
                : false;
            let hasVisibleHistoryPanel = false;

            title.textContent = version.migrationFile ? version.migrationFile.fileName : 'Baseline schema';

            if (version.migrationFile) {
                gotoBtn.style.display = 'inline-flex';
                gotoBtn.onclick = function() {
                    if (!window.AppActions || !window.AppActions.openMigrationComposer) return;
                    var filePath = version.migrationFile.filePath || '';
                    var fileName = version.migrationFile.fileName || '';
                    var directory = filePath.indexOf('/') >= 0 ? filePath.substring(0, filePath.lastIndexOf('/')) : '';
                    var baseName = fileName.replace(/\.[^.]+$/, '');
                    var derivedName = baseName.replace(/^[Vv]?\d+(?:__|[_-])?/, '') || baseName;
                    window.AppActions.openMigrationComposer({
                        mode: 'edit',
                        filePath: filePath,
                        version: version.version,
                        directory: directory,
                        name: derivedName || 'migration',
                        extension: fileName.indexOf('.') >= 0 ? fileName.split('.').pop().toLowerCase() : 'sql',
                        sql: version.migrationFile.rawContent || '',
                        title: 'Edit Migration',
                        submitLabel: 'Save Changes'
                    });
                };
                deleteBtn.style.display = 'inline-flex';
                deleteBtn.onclick = function() {
                    if (!window.__bridge || !window.__bridge.deleteMigration) return;
                    if (window.AppHelpers && window.AppHelpers.getState().settings.confirmBeforeDeleteMigration === false) {
                        window.__bridge.deleteMigration(version.migrationFile.filePath);
                        return;
                    }
                    if (window.AppUi && window.AppUi.confirm) {
                        window.AppUi.confirm({
                            title: 'Delete Migration File?',
                            message: version.migrationFile.fileName + ' will be removed from the project. This action cannot be undone from the visualizer.',
                            confirmLabel: 'Delete migration',
                            tone: 'danger',
                            onConfirm: function() {
                                window.__bridge.deleteMigration(version.migrationFile.filePath);
                            }
                        });
                        return;
                    }
                    window.__bridge.deleteMigration(version.migrationFile.filePath);
                };
            } else {
                gotoBtn.style.display = 'none';
                deleteBtn.style.display = 'none';
            }

            const tables = version.tables;
            const allTableNames = Object.keys(tables).sort();
            const changes = version.changesSummary;
            const changedTableNames = new Set([
                ...(changes && changes.tablesAdded ? changes.tablesAdded : []),
                ...(changes && changes.tablesModified ? changes.tablesModified : []),
                ...(changes && changes.tablesRemoved ? changes.tablesRemoved : [])
            ]);
            let tableNames = allTableNames.slice().sort((a, b) => {
                const aChanged = changedTableNames.has(a) ? 0 : 1;
                const bChanged = changedTableNames.has(b) ? 0 : 1;
                if (aChanged !== bChanged) return aChanged - bChanged;
                return a.localeCompare(b);
            });

            if (focusedTableName) {
                tableNames = tableNames.filter(function(name) { return name === focusedTableName; });
            } else if (window.AppHelpers && window.AppHelpers.getState().ui.timelineChangedOnly) {
                tableNames = tableNames.filter(function(name) { return changedTableNames.has(name); });
            }

            if (subtitle) {
                const subtitleBits = [
                    'Version ' + version.version
                ];
                if (changes && changes.totalStatements > 0) {
                    subtitleBits.push(changes.totalStatements + ' statement' + (changes.totalStatements !== 1 ? 's' : ''));
                }
                if (!focusedTableName && window.AppHelpers && window.AppHelpers.getState().ui.timelineChangedOnly) {
                    subtitleBits.push('changed tables only');
                } else if (!version.migrationFile) {
                    subtitleBits.push('baseline snapshot');
                }
                subtitle.textContent = subtitleBits.join(' • ');
            }

            if (historyPanel) {
                if (focusedTableName && !tableExistsAnywhere) {
                    historyPanel.style.display = 'none';
                    historyPanel.innerHTML = '';
                } else if (focusedTableName) {
                    const historyHtml = this.renderTableHistory(focusedTableName, version.version, focus && focus.columnName ? focus.columnName : null, state.schemaVersions);
                    hasVisibleHistoryPanel = !!historyHtml;
                    historyPanel.style.display = historyHtml ? 'block' : 'none';
                    historyPanel.innerHTML = historyHtml;
                } else {
                    historyPanel.style.display = 'none';
                    historyPanel.innerHTML = '';
                }
            }

            if (tableNames.length === 0) {
                if (focusedTableName && hasVisibleHistoryPanel) {
                    body.innerHTML = '';
                    return;
                }
                const showingOnlyChanged = !focusedTableName && window.AppHelpers && window.AppHelpers.getState().ui.timelineChangedOnly;
                const emptyTitle = focusedTableName
                    ? 'Selected table is not in this version'
                    : showingOnlyChanged
                        ? 'No changed tables in this migration'
                        : (version.migrationFile ? 'No tables in this migration snapshot' : 'Baseline schema is empty');
                const emptyText = focusedTableName
                    ? 'Choose another version in the timeline to see when ' + focusedTableName + ' was added, changed, or removed.'
                    : showingOnlyChanged
                        ? 'This migration does not change any table definitions. Switch back to all tables to inspect the full schema snapshot.'
                        : (version.migrationFile
                            ? 'This version does not expose any tables yet. Add schema statements or open another version from the timeline above.'
                            : 'Start by creating your first schema or migration file, then come back here to inspect the structure.');
                const emptyAction = focusedTableName
                    ? '<div style="margin-top: 18px;"><button type="button" class="btn btn-ghost btn-sm" onclick="window.AppActions && window.AppActions.clearTableHistory()">Clear Focus</button></div>'
                    : '';
                body.innerHTML = `
                    <div class="empty-state empty-state-compact" style="height: auto;">
                        <div class="empty-state-card">
                            <svg viewBox="0 0 64 64" width="56" height="56" class="empty-icon">
                                <rect x="13" y="12" width="38" height="40" rx="10" fill="none" stroke="currentColor" stroke-width="2" opacity="0.32"/>
                                <path d="M21 24h22M21 32h16M21 40h10" stroke="currentColor" stroke-width="2" opacity="0.32" stroke-linecap="round"/>
                            </svg>
                            <h3>${emptyTitle}</h3>
                            <p>${emptyText}</p>
                            ${emptyAction}
                        </div>
                    </div>
                `;
            } else {
                body.innerHTML = tableNames.map((name, i) => {
                    const table = tables[name];
                    let cardClass = 'table-card stagger-item';
                    let indicator = '';
                    let statusLabel = '';

                    if (changes) {
                        if (changes.tablesAdded.includes(name)) {
                            cardClass += ' added';
                            indicator = '<span class="change-indicator added"></span>';
                            statusLabel = '<span class="table-status-pill added">Added</span>';
                        } else if (changes.tablesModified.includes(name)) {
                            cardClass += ' modified';
                            indicator = '<span class="change-indicator modified"></span>';
                            statusLabel = '<span class="table-status-pill modified">Modified</span>';
                        }
                    }

                    if (focusedTableName === name) {
                        cardClass += ' focused';
                    }

                    const activeColumnsHtml = table.columns.map(col => {
                        let colClass = 'column-row';
                        if (changes && changes.columnsAdded && changes.columnsAdded[name] && changes.columnsAdded[name].includes(col.name)) {
                            colClass += ' added-col';
                        }
                        if (focus && focus.columnName === col.name) {
                            colClass += ' focused';
                        }

                        const pkBadge = col.isPrimaryKey ? '<span class="col-pk">PK</span>' : '';
                        const nullBadge = !col.nullable ? '<span class="col-nullable">NOT NULL</span>' : '';

                        let fkBadge = '';
                        for (const fk of table.foreignKeys) {
                            if (fk.columns.includes(col.name)) {
                                fkBadge = `<span class="col-fk">FK→${escapeHtml(fk.referencedTable)}</span>`;
                                break;
                            }
                        }

                        return `
                            <div class="${colClass}" data-column-name="${escapeHtml(col.name)}">
                                <span class="col-name">${escapeHtml(col.name)}</span>
                                ${pkBadge}${fkBadge}${nullBadge}
                                <span class="col-type">${escapeHtml(col.type)}</span>
                                <button type="button" class="inline-source-btn" title="Focus column lineage" onclick="event.stopPropagation(); window.AppActions && window.AppActions.showTableHistory('${escapeJs(name)}', ${version.version}, '${escapeJs(col.name)}')">Lineage</button>
                                <button type="button" class="inline-source-btn" title="Open related schema file" onclick="event.stopPropagation(); window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(name)}',columnName:'${escapeJs(col.name)}'}))">SQL</button>
                            </div>
                        `;
                    }).join('');

                    const removedColumns = changes && changes.removedColumnDefs && changes.removedColumnDefs[name]
                        ? changes.removedColumnDefs[name]
                        : [];
                    const removedColumnsHtml = removedColumns.map(col => {
                        const pkBadge = col.isPrimaryKey ? '<span class="col-pk">PK</span>' : '';
                        const nullBadge = !col.nullable ? '<span class="col-nullable">NOT NULL</span>' : '';
                        return `
                            <div class="column-row removed-col" data-column-name="${escapeHtml(col.name)}">
                                <span class="col-name">${escapeHtml(col.name)}</span>
                                ${pkBadge}${nullBadge}
                                <span class="col-type">${escapeHtml(col.type)}</span>
                                <button type="button" class="inline-source-btn" title="Focus column lineage" onclick="event.stopPropagation(); window.AppActions && window.AppActions.showTableHistory('${escapeJs(name)}', ${version.version}, '${escapeJs(col.name)}')">Lineage</button>
                                <button type="button" class="inline-source-btn" title="Open related schema file" onclick="event.stopPropagation(); window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(name)}',columnName:'${escapeJs(col.name)}'}))">SQL</button>
                            </div>
                        `;
                    }).join('');
                    const columnsHtml = activeColumnsHtml + removedColumnsHtml;

                    return `
                        <div class="${cardClass}" data-table-name="${escapeHtml(name)}" style="animation-delay: ${i * 0.03}s">
                            <div class="table-card-header">
                                <span class="table-name">${indicator}${escapeHtml(name)}</span>
                                <div class="table-card-meta">
                                    ${statusLabel}
                                    <span class="table-col-count">${table.columns.length} col${table.columns.length !== 1 ? 's' : ''}</span>
                                    <button type="button" class="inline-source-btn" title="Focus table history" onclick="event.stopPropagation(); window.AppActions && window.AppActions.showTableHistory('${escapeJs(name)}', ${version.version})">History</button>
                                    <button type="button" class="inline-source-btn" title="Open related schema file" onclick="event.stopPropagation(); window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(name)}'}))">Open SQL</button>
                                </div>
                            </div>
                            <div class="table-card-body">
                                ${columnsHtml}
                            </div>
                        </div>
                    `;
                }).join('');
            }

            if (changes && (changes.tablesAdded.length > 0 || changes.tablesRemoved.length > 0 || changes.tablesModified.length > 0)) {
                changesSummaryEl.style.display = 'block';
                let items = '';

                changes.tablesAdded.forEach(t => {
                    items += `<div class="change-item"><span class="change-icon add">+</span> Table <strong>${escapeHtml(t)}</strong> created</div>`;
                });
                changes.tablesRemoved.forEach(t => {
                    items += `<div class="change-item"><span class="change-icon remove">−</span> Table <strong>${escapeHtml(t)}</strong> dropped</div>`;
                });
                changes.tablesModified.forEach(t => {
                    const addedCols = changes.columnsAdded[t] || [];
                    const removedCols = changes.columnsRemoved[t] || [];
                    const details = [];
                    if (addedCols.length > 0) details.push(`+${addedCols.join(', +')}`);
                    if (removedCols.length > 0) details.push(`−${removedCols.join(', −')}`);
                    items += `<div class="change-item"><span class="change-icon modify">~</span> Table <strong>${escapeHtml(t)}</strong> modified ${details.length > 0 ? `(${details.join('; ')})` : ''}</div>`;
                });

                changesList.innerHTML = items;
            } else {
                changesSummaryEl.style.display = 'none';
            }

            var rawSqlContainer = document.getElementById('raw-sql-section');
            if (version.migrationFile && version.migrationFile.rawContent) {
                rawSqlContainer.style.display = 'block';
                var contentEl = document.getElementById('raw-sql-body');
                var highlighted = window.SqlHighlighter
                    ? window.SqlHighlighter.highlight(version.migrationFile.rawContent)
                    : escapeHtml(version.migrationFile.rawContent);
                contentEl.innerHTML = '<pre class="sql-block">' + highlighted + '</pre>';
                contentEl.classList.remove('expanded');
            } else {
                rawSqlContainer.style.display = 'none';
            }

            if (focus && focus.tableName) {
                window.requestAnimationFrame(function() {
                    var tableSelector = '.table-card[data-table-name="' + escapeCss(focus.tableName) + '"]';
                    var card = document.querySelector(tableSelector);
                    if (!card) return;
                    card.classList.add('search-highlight');
                    window.setTimeout(function() {
                        card.classList.remove('search-highlight');
                    }, 1800);
                    if (!focus.columnName) return;
                    var columnSelector = '.column-row[data-column-name="' + escapeCss(focus.columnName) + '"]';
                    var column = card.querySelector(columnSelector);
                    if (column) {
                        column.classList.add('search-highlight');
                        window.setTimeout(function() {
                            column.classList.remove('search-highlight');
                        }, 1800);
                    }
                });
            }
        },

        renderTableHistory: function(tableName, currentVersionNumber, columnName, versions) {
            const history = this.buildTableHistory(tableName, versions || []);
            if (history.length === 0) {
                return '';
            }
            const columnHistory = columnName ? this.buildColumnHistory(tableName, columnName, versions || []) : [];
            const currentVersion = (versions || []).find(function(version) { return version.version === currentVersionNumber; }) || null;
            const currentTable = currentVersion && currentVersion.tables ? currentVersion.tables[tableName] : null;
            const currentSummary = currentTable
                ? currentTable.columns.length + ' column' + (currentTable.columns.length !== 1 ? 's' : '') + ' in the selected version'
                : 'This table is not present in the selected version';
            const focusMeta = columnName ? `<span class="table-history-focus-chip">Column: ${escapeHtml(columnName)}</span>` : '';

            const itemsHtml = history.length > 0
                ? history.map(function(item) {
                    const diffButton = item.fromVersion != null
                        ? `<button type="button" class="btn btn-ghost btn-sm" onclick="window.AppActions && window.AppActions.compareVersions(${item.fromVersion}, ${item.version})">Compare</button>`
                        : '';
                    return `
                        <div class="table-history-item ${item.status}">
                            <div class="table-history-item-header">
                                <div>
                                    <div class="table-history-item-title">${escapeHtml(item.title)}</div>
                                    <div class="table-history-item-meta">Version ${item.version}${item.fileLabel ? ' • ' + escapeHtml(item.fileLabel) : ''}</div>
                                </div>
                                <span class="table-history-status ${item.status}">${escapeHtml(item.statusLabel)}</span>
                            </div>
                            <div class="table-history-item-summary">${escapeHtml(item.summary)}</div>
                            ${item.changeLines.length > 0 ? `<div class="table-history-changes">${item.changeLines.map(function(line) { return '<span class="table-history-change-chip">' + escapeHtml(line) + '</span>'; }).join('')}</div>` : ''}
                            <div class="table-history-actions">
                                <button type="button" class="btn btn-ghost btn-sm" onclick="window.AppActions && window.AppActions.showTableHistory('${escapeJs(tableName)}', ${item.version})">View Version</button>
                                ${diffButton}
                            </div>
                        </div>
                    `;
                }).join('')
                : '<div class="table-history-empty">No history entries found for this table yet.</div>';

            const columnItemsHtml = columnHistory.length > 0
                ? columnHistory.map(function(item) {
                    return `
                        <div class="table-history-item ${item.status}">
                            <div class="table-history-item-header">
                                <div>
                                    <div class="table-history-item-title">${escapeHtml(item.title)}</div>
                                    <div class="table-history-item-meta">Version ${item.version}${item.fileLabel ? ' • ' + escapeHtml(item.fileLabel) : ''}</div>
                                </div>
                                <span class="table-history-status ${item.status}">${escapeHtml(item.statusLabel)}</span>
                            </div>
                            <div class="table-history-item-summary">${escapeHtml(item.summary)}</div>
                            ${item.changeLines.length > 0 ? `<div class="table-history-changes">${item.changeLines.map(function(line) { return '<span class="table-history-change-chip">' + escapeHtml(line) + '</span>'; }).join('')}</div>` : ''}
                        </div>
                    `;
                }).join('')
                : '<div class="table-history-empty">No lineage entries found for this column yet.</div>';

            return `
                <section class="table-history-card">
                    <div class="table-history-header">
                        <div>
                            <div class="table-history-eyebrow">Table History</div>
                            <h3>${escapeHtml(tableName)}</h3>
                            <p>${escapeHtml(currentSummary)}</p>
                        </div>
                        <div class="table-history-header-actions">
                            ${focusMeta}
                            <button type="button" class="btn btn-ghost btn-sm" onclick="window.__bridge && window.__bridge.openRelatedSchemaSource && window.__bridge.openRelatedSchemaSource(JSON.stringify({tableName:'${escapeJs(tableName)}'}))">Open SQL</button>
                            <button type="button" class="btn btn-ghost btn-sm" onclick="window.AppActions && window.AppActions.clearTableHistory()">Clear Focus</button>
                        </div>
                    </div>
                    <div class="table-history-list">${itemsHtml}</div>
                    ${columnName ? `
                        <div class="table-history-column-section">
                            <div class="table-history-column-heading">Column Lineage</div>
                            <div class="table-history-list">${columnItemsHtml}</div>
                        </div>
                    ` : ''}
                </section>
            `;
        },

        buildTableHistory: function(tableName, versions) {
            const items = [];

            for (let index = 0; index < versions.length; index++) {
                const version = versions[index];
                const previous = index > 0 ? versions[index - 1] : null;
                const previousTable = previous && previous.tables ? previous.tables[tableName] : null;
                const currentTable = version.tables ? version.tables[tableName] : null;

                if (!previousTable && !currentTable) {
                    continue;
                }

                if (!previousTable && currentTable) {
                    items.push({
                        status: version.migrationFile ? 'added' : 'baseline',
                        statusLabel: version.migrationFile ? 'Added' : 'Baseline',
                        title: version.migrationFile ? 'Table introduced' : 'Present in baseline schema',
                        summary: currentTable.columns.length + ' column' + (currentTable.columns.length !== 1 ? 's' : '') + ' available from this point.',
                        changeLines: currentTable.columns.slice(0, 4).map(function(column) { return '+' + column.name; }),
                        version: version.version,
                        fromVersion: previous ? previous.version : null,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                    continue;
                }

                if (previousTable && !currentTable) {
                    items.push({
                        status: 'removed',
                        statusLabel: 'Removed',
                        title: 'Table removed',
                        summary: 'This table disappears from the schema at this version.',
                        changeLines: previousTable.columns.slice(0, 4).map(function(column) { return '−' + column.name; }),
                        version: version.version,
                        fromVersion: previous ? previous.version : null,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                    continue;
                }

                const diff = this.describeTableDelta(previousTable, currentTable);
                if (diff.length > 0) {
                    items.push({
                        status: 'modified',
                        statusLabel: 'Updated',
                        title: 'Table changed',
                        summary: diff[0],
                        changeLines: diff.slice(1),
                        version: version.version,
                        fromVersion: previous ? previous.version : null,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                }
            }

            return items.reverse();
        },

        buildColumnHistory: function(tableName, columnName, versions) {
            const items = [];

            for (let index = 0; index < versions.length; index++) {
                const version = versions[index];
                const previous = index > 0 ? versions[index - 1] : null;
                const previousColumn = previous && previous.tables && previous.tables[tableName]
                    ? previous.tables[tableName].columns.find(function(column) { return column.name === columnName; }) || null
                    : null;
                const currentColumn = version && version.tables && version.tables[tableName]
                    ? version.tables[tableName].columns.find(function(column) { return column.name === columnName; }) || null
                    : null;

                if (!previousColumn && !currentColumn) {
                    continue;
                }

                if (!previousColumn && currentColumn) {
                    items.push({
                        status: version.migrationFile ? 'added' : 'baseline',
                        statusLabel: version.migrationFile ? 'Added' : 'Baseline',
                        title: 'Column introduced',
                        summary: this.renderColumnSummary(currentColumn),
                        changeLines: [
                            currentColumn.type,
                            currentColumn.nullable ? 'NULL allowed' : 'NOT NULL'
                        ],
                        version: version.version,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                    continue;
                }

                if (previousColumn && !currentColumn) {
                    items.push({
                        status: 'removed',
                        statusLabel: 'Removed',
                        title: 'Column removed',
                        summary: columnName + ' disappears from ' + tableName + ' at this version.',
                        changeLines: [previousColumn.type],
                        version: version.version,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                    continue;
                }

                const delta = this.describeColumnDelta(previousColumn, currentColumn);
                if (delta.length > 0) {
                    items.push({
                        status: 'modified',
                        statusLabel: 'Updated',
                        title: 'Column changed',
                        summary: delta[0],
                        changeLines: delta.slice(1),
                        version: version.version,
                        fileLabel: version.migrationFile ? version.migrationFile.fileName : 'Baseline'
                    });
                }
            }

            return items.reverse();
        },

        tableExistsInAnyVersion: function(tableName, versions) {
            return (versions || []).some(function(version) {
                return !!(version && version.tables && version.tables[tableName]);
            });
        },

        describeTableDelta: function(previousTable, currentTable) {
            const lines = [];
            const previousColumns = {};
            const currentColumns = {};
            previousTable.columns.forEach(function(column) { previousColumns[column.name] = column; });
            currentTable.columns.forEach(function(column) { currentColumns[column.name] = column; });

            const added = currentTable.columns
                .filter(function(column) { return !previousColumns[column.name]; })
                .map(function(column) { return '+' + column.name; });
            const removed = previousTable.columns
                .filter(function(column) { return !currentColumns[column.name]; })
                .map(function(column) { return '−' + column.name; });
            const modified = currentTable.columns.reduce(function(acc, column) {
                const previousColumn = previousColumns[column.name];
                if (!previousColumn) return acc;

                const bits = [];
                if (previousColumn.type !== column.type) bits.push('type');
                if (!!previousColumn.nullable !== !!column.nullable) bits.push('nullability');
                if ((previousColumn.defaultValue || null) !== (column.defaultValue || null)) bits.push('default');
                if (!!previousColumn.isPrimaryKey !== !!column.isPrimaryKey) bits.push('primary key');
                if (bits.length > 0) {
                    acc.push('~' + column.name + ' (' + bits.join(', ') + ')');
                }
                return acc;
            }, []);

            if (added.length > 0) {
                lines.push('Added ' + added.length + ' column' + (added.length !== 1 ? 's' : ''));
            }
            if (removed.length > 0) {
                lines.push('Removed ' + removed.length + ' column' + (removed.length !== 1 ? 's' : ''));
            }
            if (modified.length > 0) {
                lines.push('Updated ' + modified.length + ' column' + (modified.length !== 1 ? 's' : ''));
            }

            const previousPk = JSON.stringify((previousTable.primaryKey || []).slice().sort());
            const currentPk = JSON.stringify((currentTable.primaryKey || []).slice().sort());
            if (previousPk !== currentPk) {
                lines.push('Primary key changed');
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

            if (fkSignature(previousTable) !== fkSignature(currentTable)) {
                lines.push('Foreign keys changed');
            }

            return lines.concat(added, removed, modified).slice(0, 8);
        },

        describeColumnDelta: function(previousColumn, currentColumn) {
            const lines = [];
            if (previousColumn.type !== currentColumn.type) {
                lines.push('Type changed');
                lines.push(previousColumn.type + ' -> ' + currentColumn.type);
            }
            if (!!previousColumn.nullable !== !!currentColumn.nullable) {
                lines.push('Nullability changed');
                lines.push((previousColumn.nullable ? 'NULL' : 'NOT NULL') + ' -> ' + (currentColumn.nullable ? 'NULL' : 'NOT NULL'));
            }
            if ((previousColumn.defaultValue || null) !== (currentColumn.defaultValue || null)) {
                lines.push('Default value changed');
                lines.push((previousColumn.defaultValue || 'none') + ' -> ' + (currentColumn.defaultValue || 'none'));
            }
            if (!!previousColumn.isPrimaryKey !== !!currentColumn.isPrimaryKey) {
                lines.push('Primary key membership changed');
                lines.push((previousColumn.isPrimaryKey ? 'part of PK' : 'not in PK') + ' -> ' + (currentColumn.isPrimaryKey ? 'part of PK' : 'not in PK'));
            }
            return lines.slice(0, 6);
        },

        renderColumnSummary: function(column) {
            const bits = [column.type];
            bits.push(column.nullable ? 'NULL allowed' : 'NOT NULL');
            if (column.defaultValue) {
                bits.push('default ' + column.defaultValue);
            }
            if (column.isPrimaryKey) {
                bits.push('primary key');
            }
            return bits.join(' • ');
        }
    };

    window.TimelineModule = TimelineModule;
})();
