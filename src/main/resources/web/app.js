/**
 * SQL Migration Visualizer — Main Application Controller
 *
 * Manages tab switching, data reception from Kotlin bridge,
 * and coordinates between timeline, diff, ER diagram, and validation views.
 */
(function() {
    'use strict';

    // ===== State =====
    const state = {
        schemaVersions: [],
        validationResult: null,
        pendingMigration: {
            hasPendingChanges: false,
            generatedSql: '',
            summary: '',
            suggestedVersion: 0,
            suggestedName: '',
            suggestedFileName: '',
            changeHighlights: [],
            risk: {
                level: 'LOW',
                score: 0,
                headline: '',
                items: []
            }
        },
        selectedVersion: -1,
        activeTab: 'timeline',
        settings: {
            showBaselineInTimeline: true,
            autoExpandTableCards: true,
            defaultTab: 'timeline',
            preferredSqlDialect: 'generic',
            erShowGrid: true,
            erLayoutColumns: 0,
            diffShowUnchangedColumns: true,
            rememberDiffSelections: true,
            lastDiffFromVersion: 0,
            lastDiffToVersion: 0,
            searchResultLimit: 20,
            validateOnRefresh: true,
            suggestPendingMigrationOnSave: true,
            confirmBeforeDeleteMigration: true,
            autoOpenCreatedMigration: true,
            defaultMigrationDirectory: '',
            additionalMigrationDirectories: '',
            migrationFileNamePattern: '{version}'
        },
        ui: {
            timelineChangedOnly: false,
            timelineFocus: null,
            diffFocus: null,
            erFocusTable: null,
            initialSettingsApplied: false,
            collapsedSections: {
                utilityBar: false,
                pendingBanner: false,
                timelineStrip: false,
                schemaDetail: false,
                changesSummary: false
            }
        }
    };

    const UI_PREFERENCES_KEY = 'sql-migration-visualizer-ui';
    loadPersistedUiPreferences();

    // ===== Data Reception from Kotlin Bridge =====
    window.__onSchemaData = function(jsonStr) {
        try {
            state.schemaVersions = JSON.parse(jsonStr);
            console.log('[App] Received schema data:', state.schemaVersions.length, 'versions');
            updateAll();
        } catch (e) {
            console.error('[App] Failed to parse schema data:', e);
        }
    };

    window.__onValidationData = function(jsonStr) {
        try {
            state.validationResult = JSON.parse(jsonStr);
            console.log('[App] Received validation data');
            updateValidationBadge();
            if (state.activeTab === 'validation') {
                renderValidation();
            }
        } catch (e) {
            console.error('[App] Failed to parse validation data:', e);
        }
    };

    window.__onSettingsChanged = function(jsonStr) {
        try {
            const previousDefaultTab = state.settings.defaultTab;
            state.settings = JSON.parse(jsonStr);
            console.log('[App] Received settings');
            updatePendingMigrationUi();
            updateAll();
            const initialTab = state.activeTab || state.settings.defaultTab || 'timeline';
            if ((!state.ui.initialSettingsApplied || previousDefaultTab !== initialTab) &&
                document.querySelector(`.tab[data-tab="${initialTab}"]`)) {
                state.ui.initialSettingsApplied = true;
                switchTab(initialTab);
            }
        } catch (e) {
            console.error('[App] Failed to parse settings:', e);
        }
    };

    window.__onPendingMigrationData = function(jsonStr) {
        try {
            state.pendingMigration = JSON.parse(jsonStr);
            console.log('[App] Received pending migration state');
            updatePendingMigrationUi();
        } catch (e) {
            console.error('[App] Failed to parse pending migration state:', e);
        }
    };

    // ===== Tab Switching =====
    document.getElementById('tab-bar').addEventListener('click', function(e) {
        const tab = e.target.closest('.tab');
        if (!tab) return;

        const tabId = tab.dataset.tab;
        switchTab(tabId);
    });

    function switchTab(tabId) {
        state.activeTab = tabId;
        persistUiPreferences();

        // Update tab buttons
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelector(`.tab[data-tab="${tabId}"]`).classList.add('active');

        // Update panels
        document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
        document.getElementById(`panel-${tabId}`).classList.add('active');

        renderActivePanel();
    }

    // ===== Refresh Button =====
    document.getElementById('btn-refresh').addEventListener('click', function() {
        if (window.__bridge) {
            window.__bridge.requestRefresh();
        }
    });

    initializeResponsiveLayout();
    initializeCustomDropdowns();
    bindUiControls();
    initializeSectionVisibilityControls();

    function initializeResponsiveLayout() {
        const appRoot = document.getElementById('app');
        if (!appRoot) return;

        let lastWidth = -1;
        let lastHeight = -1;

        function updateResponsiveLayout() {
            const rect = appRoot.getBoundingClientRect();
            const width = Math.round(rect.width || window.innerWidth || document.documentElement.clientWidth || 0);
            const height = Math.round(rect.height || window.innerHeight || document.documentElement.clientHeight || 0);

            if (width === lastWidth && height === lastHeight) {
                return;
            }

            lastWidth = width;
            lastHeight = height;

            appRoot.classList.toggle('compact-width', width <= 860);
            appRoot.classList.toggle('very-compact', width <= 620);
            appRoot.classList.toggle('ultra-compact', width <= 460);
            appRoot.classList.toggle('short-height', height <= 780);
            appRoot.classList.toggle('very-short', height <= 620);

            closeAllVersionDropdowns();
        }

        updateResponsiveLayout();

        if (typeof ResizeObserver !== 'undefined') {
            const resizeObserver = new ResizeObserver(function() {
                updateResponsiveLayout();
            });
            resizeObserver.observe(appRoot);
            appRoot.__resizeObserver = resizeObserver;
        }

        window.addEventListener('resize', updateResponsiveLayout);
    }

    function bindUiControls() {
        const filterAllButton = document.getElementById('btn-timeline-filter-all');
        const filterChangedButton = document.getElementById('btn-timeline-filter-changed');
        const validationIssues = document.getElementById('validation-issues');

        if (filterAllButton) {
            filterAllButton.addEventListener('click', function() {
                setTimelineFilter(false);
            });
        }

        if (filterChangedButton) {
            filterChangedButton.addEventListener('click', function() {
                setTimelineFilter(true);
            });
        }

        if (validationIssues) {
            validationIssues.addEventListener('click', handleValidationIssueClick);
        }

        syncTimelineFilterButtons();
    }

    function initializeSectionVisibilityControls() {
        document.addEventListener('click', function(event) {
            const toggleButton = event.target.closest('.section-inline-toggle, .section-header-toggle');
            if (!toggleButton) return;
            event.preventDefault();
            event.stopPropagation();
            const sectionKey = toggleButton.dataset.sectionToggle;
            if (!sectionKey) return;
            toggleSectionVisibility(sectionKey);
        });

        applySectionVisibility();
    }

    function toggleSectionVisibility(sectionKey) {
        if (sectionKey === 'utilityBar' || sectionKey === 'timelineStrip') {
            return;
        }
        state.ui.collapsedSections[sectionKey] = !state.ui.collapsedSections[sectionKey];
        applySectionVisibility();
        persistUiPreferences();
    }

    function applySectionVisibility() {
        forceExpandedSections();
        const sectionMap = {
            utilityBar: 'utility-bar',
            pendingBanner: 'migration-suggestion-banner',
            timelineStrip: 'timeline-container',
            schemaDetail: 'schema-detail',
            changesSummary: 'changes-summary'
        };

        Object.keys(sectionMap).forEach(function(sectionKey) {
            const target = sectionMap[sectionKey];
            const element = target.charAt(0) === '.'
                ? document.querySelector(target)
                : document.getElementById(target);
            if (!element) return;
            element.classList.toggle('section-collapsed', !!state.ui.collapsedSections[sectionKey]);
        });

        syncSectionVisibilityControls();
    }

    function syncSectionVisibilityControls() {
        document.querySelectorAll('[data-section-toggle]').forEach(function(toggleButton) {
            const sectionKey = toggleButton.dataset.sectionToggle;
            const isVisible = !state.ui.collapsedSections[sectionKey];
            toggleButton.setAttribute('aria-expanded', isVisible ? 'true' : 'false');
            toggleButton.title = (isVisible ? 'Collapse ' : 'Expand ') + getSectionLabel(sectionKey).toLowerCase();
        });
    }

    function getSectionLabel(sectionKey) {
        const labels = {
            utilityBar: 'Search & stats',
            pendingBanner: 'Pending migration banner',
            timelineStrip: 'Timeline overview',
            schemaDetail: 'Selected migration',
            changesSummary: 'Change summary'
        };
        return labels[sectionKey] || 'section';
    }

    function loadPersistedUiPreferences() {
        try {
            if (!window.localStorage) return;
            const raw = window.localStorage.getItem(UI_PREFERENCES_KEY);
            if (!raw) return;
            const parsed = JSON.parse(raw);
            if (parsed && typeof parsed === 'object') {
                if (typeof parsed.activeTab === 'string') {
                    state.activeTab = parsed.activeTab;
                }
                if (typeof parsed.timelineChangedOnly === 'boolean') {
                    state.ui.timelineChangedOnly = parsed.timelineChangedOnly;
                }
                if (parsed.collapsedSections && typeof parsed.collapsedSections === 'object') {
                    state.ui.collapsedSections = Object.assign({}, state.ui.collapsedSections, parsed.collapsedSections);
                }
            }
            forceExpandedSections();
        } catch (error) {
            console.warn('[App] Failed to load UI preferences:', error);
        }
    }

    function persistUiPreferences() {
        try {
            if (!window.localStorage) return;
            forceExpandedSections();
            window.localStorage.setItem(UI_PREFERENCES_KEY, JSON.stringify({
                activeTab: state.activeTab,
                timelineChangedOnly: !!state.ui.timelineChangedOnly,
                collapsedSections: state.ui.collapsedSections
            }));
        } catch (error) {
            console.warn('[App] Failed to save UI preferences:', error);
        }
    }

    function forceExpandedSections() {
        state.ui.collapsedSections.utilityBar = false;
        state.ui.collapsedSections.timelineStrip = false;
    }

    function renderActivePanel() {
        switch (state.activeTab) {
            case 'timeline':
                if (window.TimelineModule) window.TimelineModule.render(state);
                break;
            case 'diff':
                if (window.SchemaDiffModule) window.SchemaDiffModule.render(state);
                break;
            case 'er-diagram':
                if (window.ERDiagramModule) window.ERDiagramModule.render(state);
                break;
            case 'validation':
                renderValidation();
                break;
        }
    }

    // ===== Update All Views =====
    function updateAll() {
        updateStats();
        updateVersionSelectors();
        updatePendingMigrationUi();
        syncTimelineFilterButtons();
        renderActivePanel();
    }

    // ===== Stats Bar =====
    function updateStats() {
        const versions = state.schemaVersions;
        const latestSchema = versions.length > 0 ? versions[versions.length - 1] : null;
        const tableCount = latestSchema ? Object.keys(latestSchema.tables).length : 0;
        const migrationCount = versions.filter(v => v.migrationFile != null).length;

        document.getElementById('stat-versions').innerHTML =
            `<span class="stat-num">${versions.length}</span><span class="stat-label">Versions</span>`;
        document.getElementById('stat-tables').innerHTML =
            `<span class="stat-num">${tableCount}</span><span class="stat-label">Tables</span>`;
        document.getElementById('stat-migrations').innerHTML =
            `<span class="stat-num">${migrationCount}</span><span class="stat-label">Migrations</span>`;
    }

    function updatePendingMigrationUi() {
        const pending = state.pendingMigration || {};
        const hasPending = pending.hasPendingChanges === true;
        const banner = document.getElementById('migration-suggestion-banner');
        const title = document.getElementById('migration-suggestion-title');
        const text = document.getElementById('migration-suggestion-text');
        const meta = document.getElementById('migration-suggestion-meta');
        const riskBadge = document.getElementById('migration-suggestion-risk');
        const createButton = document.getElementById('btn-create-migration');
        const createButtonIcon = document.getElementById('btn-create-migration-icon');
        const createButtonLabel = document.getElementById('btn-create-migration-label');
        const quickCreateButton = document.getElementById('btn-create-pending-migration');
        const emptyBadge = document.querySelector('#timeline-empty .empty-state-badge');
        const emptyTitle = document.querySelector('#timeline-empty h3');
        const emptyText = document.querySelector('#timeline-empty p');
        const emptyAction = document.querySelector('#timeline-empty .btn-ghost');

        if (banner) {
            banner.style.display = hasPending ? 'flex' : 'none';
        }
        if (title) {
            title.textContent = hasPending ? 'Schema changes are ready for a suggested migration' : 'Schema changes detected';
        }
        if (text) {
            if (hasPending) {
                text.textContent = pending.summary || 'Review the suggested migration draft when you are ready to create a migration.';
            } else {
                text.textContent = 'Refresh to review the suggested migration.';
            }
        }
        if (meta) {
            if (hasPending) {
                const metaBits = [];
                if (pending.suggestedFileName) {
                    metaBits.push('Ready as ' + pending.suggestedFileName);
                } else if (pending.suggestedVersion) {
                    metaBits.push('Ready as version ' + pending.suggestedVersion);
                }
                if (pending.risk && pending.risk.headline) {
                    metaBits.push(pending.risk.headline);
                }
                meta.textContent = metaBits.join(' • ');
                meta.style.display = metaBits.length > 0 ? 'block' : 'none';
            } else {
                meta.style.display = 'none';
                meta.textContent = '';
            }
        }
        if (riskBadge) {
            if (hasPending) {
                riskBadge.className = 'risk-badge ' + getRiskBadgeClass(pending.risk);
                riskBadge.textContent = formatRiskLabel(pending.risk);
                riskBadge.style.display = 'inline-flex';
            } else {
                riskBadge.style.display = 'none';
                riskBadge.textContent = '';
            }
        }

        if (createButton) {
            createButton.classList.toggle('is-suggested', hasPending);
            createButton.title = hasPending ? 'Create the suggested migration' : 'Create new migration file';
        }
        if (createButtonIcon) {
            createButtonIcon.innerHTML = hasPending
                ? '<path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 10H8v-2h5v2zm0 4H8v-2h5v2zm0-8V3.5L18.5 9H13z" fill="currentColor"/>'
                : '<path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/>';
        }
        if (createButtonLabel) {
            createButtonLabel.textContent = hasPending ? 'Create Suggested Migration' : 'Create Migration';
        }
        if (quickCreateButton) {
            quickCreateButton.style.display = hasPending ? 'inline-flex' : 'none';
        }

        if (emptyBadge) {
            emptyBadge.textContent = hasPending ? 'Schema Changes Ready' : 'Timeline Ready';
        }
        if (emptyTitle) {
            emptyTitle.textContent = hasPending ? 'Pending migration draft available' : 'No migrations found yet';
        }
        if (emptyText) {
            emptyText.innerHTML = hasPending
                ? 'Your schema SQL changed since the last saved migration. Review the suggested migration draft when you are ready.'
                : 'Add versioned migration files to your project to explore schema history, compare versions, and validate changes in one place.';
        }
        if (emptyAction) {
            emptyAction.innerHTML = hasPending
                ? '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 10H8v-2h5v2zm0 4H8v-2h5v2zm0-8V3.5L18.5 9H13z" fill="currentColor"/></svg>Create Suggested Migration'
                : '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>Create Migration';
            emptyAction.onclick = hasPending
                ? function() { window.AppActions && window.AppActions.quickCreatePendingMigration(); }
                : function() { window.AppActions && window.AppActions.openMigrationComposer(); };
        }
    }

    // ===== Version Selectors (for Diff and ER views) =====
    function updateVersionSelectors() {
        const versions = state.schemaVersions;
        const diffFrom = document.getElementById('diff-from');
        const diffTo = document.getElementById('diff-to');
        const erVersion = document.getElementById('er-version');
        const previousValues = {
            diffFrom: diffFrom.value,
            diffTo: diffTo.value,
            erVersion: erVersion.value
        };

        [diffFrom, diffTo, erVersion].forEach(sel => {
            sel.innerHTML = '';
            versions.forEach(v => {
                const opt = document.createElement('option');
                opt.value = v.version;
                opt.textContent = `Version ${v.version}`;
                sel.appendChild(opt);
            });
        });

        if (versions.length >= 2) {
            const rememberedFrom = state.settings && state.settings.rememberDiffSelections
                ? String(state.settings.lastDiffFromVersion || '')
                : '';
            const rememberedTo = state.settings && state.settings.rememberDiffSelections
                ? String(state.settings.lastDiffToVersion || '')
                : '';
            diffFrom.value = hasVersionOption(diffFrom, previousValues.diffFrom)
                ? previousValues.diffFrom
                : hasVersionOption(diffFrom, rememberedFrom)
                    ? rememberedFrom
                    : String(versions[versions.length - 2].version);
            diffTo.value = hasVersionOption(diffTo, previousValues.diffTo)
                ? previousValues.diffTo
                : hasVersionOption(diffTo, rememberedTo)
                    ? rememberedTo
                    : String(versions[versions.length - 1].version);
        } else if (versions.length === 1) {
            diffFrom.value = String(versions[0].version);
            diffTo.value = String(versions[0].version);
        }

        if (versions.length > 0) {
            erVersion.value = hasVersionOption(erVersion, previousValues.erVersion)
                ? previousValues.erVersion
                : String(versions[versions.length - 1].version);
        }

        [diffFrom, diffTo, erVersion].forEach(syncVersionDropdown);

        // Bind change events
        diffFrom.onchange = diffTo.onchange = function() {
            persistDiffSelection();
            if (window.SchemaDiffModule) window.SchemaDiffModule.render(state);
        };
        erVersion.onchange = function() {
            if (window.ERDiagramModule) window.ERDiagramModule.render(state);
        };
    }

    function hasVersionOption(select, value) {
        if (!select || value == null || value === '') return false;
        return Array.prototype.some.call(select.options || [], function(option) {
            return String(option.value) === String(value);
        });
    }

    function syncTimelineFilterButtons() {
        const filterAllButton = document.getElementById('btn-timeline-filter-all');
        const filterChangedButton = document.getElementById('btn-timeline-filter-changed');
        if (filterAllButton) {
            filterAllButton.classList.toggle('active', !state.ui.timelineChangedOnly);
        }
        if (filterChangedButton) {
            filterChangedButton.classList.toggle('active', !!state.ui.timelineChangedOnly);
        }
    }

    function setTimelineFilter(changedOnly) {
        state.ui.timelineChangedOnly = !!changedOnly;
        syncTimelineFilterButtons();
        persistUiPreferences();
        if (state.activeTab === 'timeline' && window.TimelineModule) {
            window.TimelineModule.render(state);
        }
    }

    function focusSearchInput() {
        const input = document.getElementById('search-input');
        if (!input) return;
        input.focus();
        input.select();
    }

    function findVersionIndex(versionNumber) {
        for (var i = 0; i < state.schemaVersions.length; i++) {
            if (state.schemaVersions[i].version === versionNumber) {
                return i;
            }
        }
        return -1;
    }

    function setDiffVersions(fromVersion, toVersion) {
        const fromSelect = document.getElementById('diff-from');
        const toSelect = document.getElementById('diff-to');
        if (!fromSelect || !toSelect) return;

        if (hasVersionOption(fromSelect, fromVersion)) {
            fromSelect.value = String(fromVersion);
        }
        if (hasVersionOption(toSelect, toVersion)) {
            toSelect.value = String(toVersion);
        }

        syncVersionDropdown(fromSelect);
        syncVersionDropdown(toSelect);
    }

    function setErVersion(version) {
        const erSelect = document.getElementById('er-version');
        if (!erSelect || !hasVersionOption(erSelect, version)) return;
        erSelect.value = String(version);
        syncVersionDropdown(erSelect);
    }

    function highlightElement(element) {
        if (!element) return;
        element.classList.add('search-highlight');
        element.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
        window.setTimeout(function() {
            element.classList.remove('search-highlight');
        }, 1800);
    }

    function focusTimelineMatch(tableName, columnName) {
        window.requestAnimationFrame(function() {
            var tableSelector = '.table-card[data-table-name="' + escapeCss(tableName) + '"]';
            var card = document.querySelector(tableSelector);
            if (!card) return;
            card.classList.add('expanded');
            highlightElement(card);

            if (!columnName) return;
            var columnSelector = '.column-row[data-column-name="' + escapeCss(columnName) + '"]';
            var column = card.querySelector(columnSelector);
            if (column) {
                highlightElement(column);
            }
        });
    }

    function selectTimelineVersion(versionNumber) {
        var versions = state.schemaVersions || [];
        var showBaseline = !state.settings || state.settings.showBaselineInTimeline !== false;
        var displayVersions = showBaseline ? versions : versions.filter(function(version) {
            return version.migrationFile != null;
        });
        for (var i = 0; i < displayVersions.length; i++) {
            if (displayVersions[i].version === versionNumber) {
                state.selectedVersion = i;
                return true;
            }
        }
        return false;
    }

    function findLatestVersionContainingTable(tableName) {
        var versions = state.schemaVersions || [];
        for (var i = versions.length - 1; i >= 0; i--) {
            if (versions[i].tables && versions[i].tables[tableName]) {
                return versions[i].version;
            }
        }
        return versions.length > 0 ? versions[versions.length - 1].version : null;
    }

    function focusDiffMatch(tableName, columnName) {
        window.requestAnimationFrame(function() {
            var table = document.querySelector('.diff-table-section[data-table-name="' + escapeCss(tableName) + '"]');
            if (!table) return;
            highlightElement(table);

            if (!columnName) return;
            var column = table.querySelector('.diff-line[data-column-name="' + escapeCss(columnName) + '"]');
            if (column) {
                highlightElement(column);
            }
        });
    }

    function openSearchResult(result) {
        if (!result) return;

        var tableName = result.tableName || result.table || '';
        var columnName = result.columnName || result.column || '';
        var version = parseInt(result.version, 10);
        var targetView = result.view || 'timeline';
        if (!tableName || Number.isNaN(version)) return;

        var versionIndex = findVersionIndex(version);
        if (versionIndex < 0) return;

        if (targetView === 'timeline') {
            state.ui.erFocusTable = null;
            state.ui.timelineChangedOnly = false;
            syncTimelineFilterButtons();
            selectTimelineVersion(version);
            state.ui.timelineFocus = { tableName: tableName, columnName: columnName || null };
            switchTab('timeline');
            focusTimelineMatch(tableName, columnName);
            return;
        }

        if (targetView === 'diff') {
            state.ui.erFocusTable = null;
            var fromIndex = versionIndex > 0 ? versionIndex - 1 : versionIndex;
            var toIndex = versionIndex;
            if (versionIndex === 0 && state.schemaVersions.length > 1) {
                toIndex = 1;
            }
            var fromVersion = state.schemaVersions[fromIndex].version;
            var toVersion = state.schemaVersions[toIndex].version;
            setDiffVersions(fromVersion, toVersion);
            state.ui.diffFocus = { tableName: tableName, columnName: columnName || null };
            switchTab('diff');
            focusDiffMatch(tableName, columnName);
            return;
        }

        if (targetView === 'er') {
            setErVersion(version);
            state.ui.erFocusTable = tableName;
            switchTab('er-diagram');
        }
    }

    function buildGapDraft(issue) {
        if (!issue || !issue.missingVersions || issue.missingVersions.length === 0) {
            return '-- Add migration statements here.';
        }
        var version = issue.missingVersions[0];
        return '-- Draft migration for ' + version + '.' + getPreferredMigrationExtension() + '\n' +
            '-- Fill in the SQL statements needed for this missing version.\n';
    }

    function handleValidationIssueClick(e) {
        var button = e.target.closest('.issue-action-btn');
        if (!button) return;

        var action = button.dataset.validationAction;
        var issueIndex = parseInt(button.dataset.issueIndex, 10);
        if (!state.validationResult || Number.isNaN(issueIndex) || !state.validationResult.issues[issueIndex]) {
            return;
        }
        var issue = state.validationResult.issues[issueIndex];

        if (action === 'open-file') {
            var filePath = button.dataset.filePath || issue.filePath;
            if (filePath && window.__bridge && window.__bridge.openFile) {
                window.__bridge.openFile(filePath);
            }
            return;
        }

        if (action === 'draft-gap') {
            window.AppActions.openMigrationComposer({
                version: issue.missingVersions && issue.missingVersions.length > 0 ? issue.missingVersions[0] : null,
                sql: buildGapDraft(issue),
                title: 'Draft Missing Migration',
                submitLabel: 'Create Missing Migration'
            });
            return;
        }

        if (action === 'compare-gap') {
            if (issue.contextVersions && issue.contextVersions.length >= 2) {
                window.AppActions.compareVersions(issue.contextVersions[0], issue.contextVersions[1]);
            }
            return;
        }

        if (action === 'show-history') {
            if (issue.tableName) {
                window.AppActions.showTableHistory(issue.tableName, issue.version || issue.contextVersions[issue.contextVersions.length - 1] || null, issue.columnName || null);
            }
        }
    }

    function initializeCustomDropdowns() {
        document.addEventListener('click', function(e) {
            const trigger = e.target.closest('.version-dropdown-trigger');
            const option = e.target.closest('.version-dropdown-option');

            if (option) {
                const dropdown = option.closest('.version-dropdown');
                const selectId = dropdown ? dropdown.dataset.selectId : null;
                const select = selectId ? document.getElementById(selectId) : null;
                if (!select) return;

                select.value = option.dataset.value;
                syncVersionDropdown(select);
                closeAllVersionDropdowns();
                if (typeof select.onchange === 'function') {
                    select.onchange();
                }
                return;
            }

            if (trigger) {
                const dropdown = trigger.closest('.version-dropdown');
                if (!dropdown) return;
                const shouldOpen = !dropdown.classList.contains('open');
                closeAllVersionDropdowns();
                if (shouldOpen) {
                    openVersionDropdown(dropdown);
                }
                return;
            }

            if (!e.target.closest('.version-dropdown')) {
                closeAllVersionDropdowns();
            }
        });

        document.addEventListener('keydown', function(e) {
            const trigger = e.target.closest('.version-dropdown-trigger');
            const option = e.target.closest('.version-dropdown-option');

            if (trigger) {
                const dropdown = trigger.closest('.version-dropdown');
                if (!dropdown) return;

                if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    openVersionDropdown(dropdown, true);
                    return;
                }

                if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    openVersionDropdown(dropdown, true, true);
                    return;
                }

                if (e.key === 'Escape') {
                    closeAllVersionDropdowns();
                }
                return;
            }

            if (!option) return;

            const dropdown = option.closest('.version-dropdown');
            if (!dropdown) return;
            const options = Array.prototype.slice.call(dropdown.querySelectorAll('.version-dropdown-option'));
            const currentIndex = options.indexOf(option);

            if (e.key === 'ArrowDown') {
                e.preventDefault();
                const next = options[Math.min(options.length - 1, currentIndex + 1)];
                if (next) next.focus();
                return;
            }

            if (e.key === 'ArrowUp') {
                e.preventDefault();
                const prev = options[Math.max(0, currentIndex - 1)];
                if (prev) prev.focus();
                return;
            }

            if (e.key === 'Home') {
                e.preventDefault();
                if (options[0]) options[0].focus();
                return;
            }

            if (e.key === 'End') {
                e.preventDefault();
                if (options.length > 0) options[options.length - 1].focus();
                return;
            }

            if (e.key === 'Escape') {
                e.preventDefault();
                closeAllVersionDropdowns();
                const dropdownTrigger = dropdown.querySelector('.version-dropdown-trigger');
                if (dropdownTrigger) dropdownTrigger.focus();
                return;
            }

            if (e.key === 'Tab') {
                closeAllVersionDropdowns();
            }
        });
    }

    function closeAllVersionDropdowns() {
        document.querySelectorAll('.version-dropdown.open').forEach(function(dropdown) {
            dropdown.classList.remove('open');
            const trigger = dropdown.querySelector('.version-dropdown-trigger');
            if (trigger) {
                trigger.setAttribute('aria-expanded', 'false');
            }
        });
    }

    function openVersionDropdown(dropdown, focusSelected, focusLast) {
        if (!dropdown) return;
        dropdown.classList.add('open');
        const trigger = dropdown.querySelector('.version-dropdown-trigger');
        if (trigger) {
            trigger.setAttribute('aria-expanded', 'true');
        }

        if (!focusSelected) return;

        const options = Array.prototype.slice.call(dropdown.querySelectorAll('.version-dropdown-option'));
        if (options.length === 0) return;
        const selected = dropdown.querySelector('.version-dropdown-option.selected');
        const target = focusLast ? options[options.length - 1] : (selected || options[0]);
        if (target) {
            window.requestAnimationFrame(function() {
                target.focus();
            });
        }
    }

    function syncVersionDropdown(select) {
        if (!select) return;
        const dropdown = document.querySelector('.version-dropdown[data-select-id="' + select.id + '"]');
        if (!dropdown) return;

        const label = dropdown.querySelector('.version-dropdown-label');
        const menu = dropdown.querySelector('.version-dropdown-menu');
        if (!label || !menu) return;

        const options = Array.prototype.slice.call(select.options || []);
        menu.innerHTML = options.map(function(opt) {
            const isSelected = String(opt.value) === String(select.value);
            return '<button type="button" class="version-dropdown-option' + (isSelected ? ' selected' : '') + '"' +
                ' data-value="' + escapeHtml(String(opt.value)) + '"' +
                ' role="option" aria-selected="' + (isSelected ? 'true' : 'false') + '">' +
                escapeHtml(opt.textContent || '') +
                '</button>';
        }).join('');

        const selectedOption = options.find(function(opt) {
            return String(opt.value) === String(select.value);
        }) || options[0];

        label.textContent = selectedOption ? selectedOption.textContent : 'Select version';
    }

    function showToast(message, tone) {
        const container = document.getElementById('toast-container');
        if (!container || !message) return;

        const toast = document.createElement('div');
        toast.className = 'toast toast-' + (tone || 'success');
        toast.textContent = message;
        container.appendChild(toast);

        window.requestAnimationFrame(function() {
            toast.classList.add('visible');
        });

        window.setTimeout(function() {
            toast.classList.remove('visible');
            window.setTimeout(function() {
                if (toast.parentNode) {
                    toast.parentNode.removeChild(toast);
                }
            }, 180);
        }, 2400);
    }

    function copyText(text, successMessage) {
        if (!text) return;
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function() {
                showToast(successMessage || 'Copied.', 'success');
            }).catch(function() {
                fallbackCopyText(text, successMessage);
            });
            return;
        }
        fallbackCopyText(text, successMessage);
    }

    function fallbackCopyText(text, successMessage) {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            showToast(successMessage || 'Copied.', 'success');
        } finally {
            document.body.removeChild(textarea);
        }
    }

    function persistDiffSelection() {
        if (!state.settings || state.settings.rememberDiffSelections !== true) return;
        if (!window.__bridge || !window.__bridge.saveDiffSelection) return;

        const fromSelect = document.getElementById('diff-from');
        const toSelect = document.getElementById('diff-to');
        if (!fromSelect || !toSelect) return;

        const fromVersion = parseInt(fromSelect.value, 10);
        const toVersion = parseInt(toSelect.value, 10);
        if (Number.isNaN(fromVersion) || Number.isNaN(toVersion)) return;

        state.settings.lastDiffFromVersion = fromVersion;
        state.settings.lastDiffToVersion = toVersion;
        window.__bridge.saveDiffSelection(JSON.stringify({
            fromVersion: fromVersion,
            toVersion: toVersion
        }));
    }

    let pendingConfirmAction = null;

    function openConfirmDialog(options) {
        const modal = document.getElementById('confirm-modal');
        const title = document.getElementById('confirm-modal-title');
        const message = document.getElementById('confirm-modal-message');
        const submitButton = document.getElementById('confirm-modal-submit');
        const icon = document.getElementById('confirm-modal-icon');
        if (!modal || !title || !message || !submitButton) return;

        const opts = options || {};
        title.textContent = opts.title || 'Confirm Action';
        message.textContent = opts.message || 'Are you sure you want to continue?';
        submitButton.textContent = opts.confirmLabel || 'Confirm';
        submitButton.className = 'btn btn-sm ' + (opts.tone === 'danger' ? 'btn-danger' : 'btn-primary');
        if (icon) {
            icon.style.display = opts.hideIcon ? 'none' : 'inline-flex';
        }

        pendingConfirmAction = typeof opts.onConfirm === 'function' ? opts.onConfirm : null;
        modal.style.display = 'flex';
    }

    function closeConfirmDialog() {
        const modal = document.getElementById('confirm-modal');
        if (modal) {
            modal.style.display = 'none';
        }
        pendingConfirmAction = null;
    }

    function submitConfirmDialog() {
        const action = pendingConfirmAction;
        closeConfirmDialog();
        if (action) {
            action();
        }
    }

    // ===== Validation Badge =====
    function updateValidationBadge() {
        const badge = document.getElementById('validation-badge');
        if (!state.validationResult) {
            badge.style.display = 'none';
            return;
        }
        const errorCount = state.validationResult.issues.filter(i => i.severity === 'ERROR').length;
        const warningCount = state.validationResult.issues.filter(i => i.severity === 'WARNING').length;
        const total = errorCount + warningCount;
        if (total > 0) {
            badge.textContent = total;
            badge.style.display = 'inline-flex';
            badge.style.background = errorCount > 0 ? 'var(--color-error)' : 'var(--color-warning)';
        } else {
            badge.style.display = 'none';
        }
    }

    function renderValidationActions(issue, index) {
        var actions = [];

        if (issue.code === 'VERSION_GAP') {
            actions.push('<button type="button" class="btn btn-ghost btn-sm issue-action-btn" data-validation-action="draft-gap" data-issue-index="' + index + '">Draft Missing Version</button>');
            if (issue.contextVersions && issue.contextVersions.length >= 2) {
                actions.push('<button type="button" class="btn btn-ghost btn-sm issue-action-btn" data-validation-action="compare-gap" data-issue-index="' + index + '">Compare Around Gap</button>');
            }
        }

        if (issue.code === 'DUPLICATE_VERSION' && issue.relatedFilePaths) {
            issue.relatedFilePaths.forEach(function(filePath, fileIndex) {
                var fileName = filePath.split('/').pop();
                actions.push(
                    '<button type="button" class="btn btn-ghost btn-sm issue-action-btn" data-validation-action="open-file" data-issue-index="' + index + '" data-file-path="' + escapeHtml(filePath) + '">Open ' +
                    escapeHtml(fileName || ('File ' + (fileIndex + 1))) +
                    '</button>'
                );
            });
        } else if (issue.filePath) {
            actions.push('<button type="button" class="btn btn-ghost btn-sm issue-action-btn" data-validation-action="open-file" data-issue-index="' + index + '" data-file-path="' + escapeHtml(issue.filePath) + '">Open File</button>');
        }

        if (issue.tableName) {
            actions.push('<button type="button" class="btn btn-ghost btn-sm issue-action-btn" data-validation-action="show-history" data-issue-index="' + index + '">View Table History</button>');
        }

        if (actions.length === 0) {
            return '';
        }

        return '<div class="issue-actions">' + actions.join('') + '</div>';
    }

    // ===== Validation Rendering =====
    function renderValidation() {
        const result = state.validationResult;
        const summaryEl = document.getElementById('validation-summary');
        const issuesEl = document.getElementById('validation-issues');

        if (!result) {
            summaryEl.innerHTML = `
                <div class="validation-summary-card">
                    <div class="validation-summary-text">No validation data available. Click Refresh to scan.</div>
                </div>
            `;
            issuesEl.innerHTML = '';
            return;
        }

        const iconSvg = result.isValid
            ? '<svg viewBox="0 0 24 24" width="18" height="18"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" fill="currentColor"/></svg>'
            : '<svg viewBox="0 0 24 24" width="18" height="18"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" fill="currentColor"/></svg>';
        const errorCount = result.issues.filter(function(issue) { return issue.severity === 'ERROR'; }).length;
        const warningCount = result.issues.filter(function(issue) { return issue.severity === 'WARNING'; }).length;
        const infoCount = result.issues.filter(function(issue) { return issue.severity === 'INFO'; }).length;

        summaryEl.innerHTML = `
            <div class="validation-summary-card ${result.isValid ? 'valid' : 'invalid'}">
                <div class="validation-icon ${result.isValid ? 'valid' : 'invalid'}">${iconSvg}</div>
                <div class="validation-summary-copy">
                    <div class="validation-summary-text">${escapeHtml(result.summary)}</div>
                    <div class="validation-summary-pills">
                        <span class="validation-pill error"><strong>${errorCount}</strong> errors</span>
                        <span class="validation-pill warning"><strong>${warningCount}</strong> warnings</span>
                        <span class="validation-pill info"><strong>${infoCount}</strong> info</span>
                    </div>
                </div>
            </div>
        `;

        if (result.issues.length === 0) {
            issuesEl.innerHTML = `
                <div class="empty-state" style="height: auto; padding: 40px">
                    <h3 style="color: var(--color-success)">All checks passed!</h3>
                    <p>No issues found in your migration files.</p>
                </div>
            `;
            return;
        }

        issuesEl.innerHTML = result.issues.map((issue, i) => {
            const severityClass = issue.severity.toLowerCase();
            const severityLabel = { ERROR: 'E', WARNING: 'W', INFO: 'I' }[issue.severity] || '?';

            let metaHtml = '';
            if (issue.version != null || issue.filePath) {
                metaHtml = '<div class="issue-meta">';
                if (issue.version != null) {
                    metaHtml += `<span class="issue-tag">v${issue.version}</span>`;
                }
                if (issue.filePath) {
                    metaHtml += `<span class="issue-file-link" onclick="window.__bridge && window.__bridge.openFile('${escapeJs(issue.filePath)}')">${escapeHtml(issue.filePath.split('/').pop())}</span>`;
                }
                metaHtml += '</div>';
            }

            return `
                <div class="issue-card ${severityClass} stagger-item" style="animation-delay: ${i * 0.03}s">
                    <div class="issue-severity ${severityClass}">${severityLabel}</div>
                    <div class="issue-content">
                        <div class="issue-message">${escapeHtml(issue.message)}</div>
                        ${issue.explanation ? `<div class="issue-guidance"><span class="issue-guidance-label">Why it matters</span><div class="issue-details">${escapeHtml(issue.explanation)}</div></div>` : ''}
                        ${issue.suggestedFix ? `<div class="issue-guidance"><span class="issue-guidance-label">Suggested fix</span><div class="issue-details">${escapeHtml(issue.suggestedFix)}</div></div>` : ''}
                        ${issue.details ? `<div class="issue-guidance"><span class="issue-guidance-label">Details</span><div class="issue-details">${escapeHtml(issue.details)}</div></div>` : ''}
                        ${metaHtml}
                        ${renderValidationActions(issue, i)}
                    </div>
                </div>
            `;
        }).join('');
    }

    // ===== Helpers =====
    window.AppActions = {
        handlePrimaryCreateAction: function() {
            if (state.pendingMigration && state.pendingMigration.hasPendingChanges) {
                this.quickCreatePendingMigration();
                return;
            }
            this.openMigrationComposer();
        },
        openGeneratedMigrationAsDraft: function() {
            const modal = document.getElementById('migration-modal');
            if (!modal || !window.CreateMigrationModule) return;
            const sql = modal._rawSql || '';
            modal.style.display = 'none';
            window.CreateMigrationModule.openModal({
                sql: sql,
                name: inferMigrationNameFromSql(sql),
                sourceKind: 'generated',
                summary: 'Generated from the selected schema diff. Review the SQL and save it as a migration when it looks right.'
            });
            if (sql) {
                showToast('Generated SQL moved into the migration editor.', 'info');
            }
        },
        openMigrationComposer: function(options) {
            if (!window.CreateMigrationModule) return;
            var opts = options || {};
            if ((!options || Object.keys(opts).length === 0) && state.pendingMigration && state.pendingMigration.hasPendingChanges) {
                showToast('Pending migration draft loaded for review.', 'info');
                window.CreateMigrationModule.openModal({
                    sql: state.pendingMigration.generatedSql,
                    name: state.pendingMigration.suggestedName || inferMigrationNameFromSql(state.pendingMigration.generatedSql),
                    version: state.pendingMigration.suggestedVersion || undefined,
                    suggestedFileName: state.pendingMigration.suggestedFileName || '',
                    summary: state.pendingMigration.summary || '',
                    changeHighlights: state.pendingMigration.changeHighlights || [],
                    risk: state.pendingMigration.risk || null,
                    sourceKind: 'pending'
                });
            } else {
                window.CreateMigrationModule.openModal(opts);
            }
        },
        showTableHistory: function(tableName, preferredVersion, columnName) {
            if (!tableName) return;
            state.ui.erFocusTable = null;
            state.ui.timelineChangedOnly = false;
            syncTimelineFilterButtons();
            state.ui.timelineFocus = {
                tableName: tableName,
                columnName: columnName || null
            };
            var targetVersion = preferredVersion || findLatestVersionContainingTable(tableName);
            if (targetVersion != null) {
                selectTimelineVersion(targetVersion);
            }
            switchTab('timeline');
            focusTimelineMatch(tableName, columnName || null);
        },
        clearTableHistory: function() {
            state.ui.timelineFocus = null;
            if (state.activeTab === 'timeline' && window.TimelineModule) {
                window.TimelineModule.render(state);
            }
        },
        compareVersions: function(fromVersion, toVersion) {
            if (fromVersion == null || toVersion == null) return;
            setDiffVersions(fromVersion, toVersion);
            persistDiffSelection();
            switchTab('diff');
            if (window.SchemaDiffModule) {
                window.SchemaDiffModule.render(state);
            }
        },
        quickCreatePendingMigration: function() {
            if (!state.pendingMigration || !state.pendingMigration.hasPendingChanges) {
                this.openMigrationComposer();
                return;
            }
            if (!window.__bridge || !window.__bridge.createMigration || !window.CreateMigrationModule) {
                this.openMigrationComposer();
                return;
            }

            var defaults = window.CreateMigrationModule.getSuggestedDefaults
                ? window.CreateMigrationModule.getSuggestedDefaults()
                : { version: 1, directory: window.__defaultMigrationDir || '' };

            if (!defaults.directory) {
                showToast('Choose a migration directory once, then future suggested migrations can be created in one click.', 'info');
                window.CreateMigrationModule.openModal({
                    sql: state.pendingMigration.generatedSql,
                    name: state.pendingMigration.suggestedName || inferMigrationNameFromSql(state.pendingMigration.generatedSql),
                    version: state.pendingMigration.suggestedVersion || defaults.version,
                    suggestedFileName: state.pendingMigration.suggestedFileName || '',
                    summary: state.pendingMigration.summary || '',
                    changeHighlights: state.pendingMigration.changeHighlights || [],
                    risk: state.pendingMigration.risk || null,
                    sourceKind: 'pending'
                });
                return;
            }

            window.__bridge.createMigration(JSON.stringify({
                version: state.pendingMigration.suggestedVersion || defaults.version,
                directory: defaults.directory,
                content: state.pendingMigration.generatedSql,
                name: state.pendingMigration.suggestedName || inferMigrationNameFromSql(state.pendingMigration.generatedSql)
            }));
        },
        cancelPendingMigration: function() {
            if (!state.pendingMigration || !state.pendingMigration.hasPendingChanges) return;
            openConfirmDialog({
                title: 'Dismiss Suggested Migration?',
                message: 'This suggested migration draft will be cleared and the current schema state will become the new baseline for future suggestions.',
                confirmLabel: 'Dismiss Draft',
                tone: 'danger',
                onConfirm: function() {
                    if (window.__bridge && window.__bridge.dismissPendingMigration) {
                        window.__bridge.dismissPendingMigration();
                    }
                }
            });
        },
        switchTab: switchTab,
        focusSearch: focusSearchInput,
        setTimelineChangedOnly: setTimelineFilter,
        openSearchResult: openSearchResult
    };

    window.AppHelpers = {
        getSchemaVersion: function(version) {
            return state.schemaVersions.find(v => v.version === version) || null;
        },
        setSelectedVersion: function(version) {
            state.selectedVersion = version;
        },
        findVersionIndex: findVersionIndex,
        findLatestVersionContainingTable: findLatestVersionContainingTable,
        setDiffVersions: setDiffVersions,
        setErVersion: setErVersion,
        syncVersionDropdown: syncVersionDropdown,
        getPreferredMigrationExtension: getPreferredMigrationExtension,
        getSuggestedMigrationFileName: function(optionsOrBaseName, maybeVersion, maybeName) {
            var options;
            if (typeof optionsOrBaseName === 'string') {
                options = {
                    name: optionsOrBaseName,
                    version: maybeVersion,
                    explicitName: maybeName
                };
            } else {
                options = optionsOrBaseName || {};
            }
            return buildSuggestedMigrationFileName(options);
        },
        inferMigrationNameFromSql: inferMigrationNameFromSql,
        getState: function() {
            return state;
        }
    };

    window.AppUi = {
        showToast: showToast,
        copyText: copyText,
        confirm: openConfirmDialog,
        closeConfirm: closeConfirmDialog,
        submitConfirm: submitConfirmDialog,
        renderRiskBadge: renderRiskBadge,
        renderRiskList: renderRiskList,
        formatRiskLabel: formatRiskLabel
    };

    window.__onRelatedSchemaOpenFailed = function(message) {
        showToast(message || 'Could not find a related schema file.', 'info');
    };

    window.__onMigrationDeleted = function(filePath) {
        const fileName = filePath ? filePath.split('/').pop() : 'Migration file';
        showToast(fileName + ' deleted successfully.', 'success');
    };

    window.__onPendingMigrationDismissed = function() {
        showToast('Pending migration dismissed.', 'info');
    };

    function escapeHtml(str) {
        if (!str) return '';
        return str.replace(/&/g, '&amp;')
                  .replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;')
                  .replace(/"/g, '&quot;');
    }

    function getRiskBadgeClass(risk) {
        var level = risk && risk.level ? String(risk.level).toUpperCase() : 'LOW';
        if (level === 'HIGH') return 'risk-badge-high';
        if (level === 'MEDIUM') return 'risk-badge-medium';
        return 'risk-badge-low';
    }

    function formatRiskLabel(risk) {
        var level = risk && risk.level ? String(risk.level).toUpperCase() : 'LOW';
        if (level === 'HIGH') return 'High risk';
        if (level === 'MEDIUM') return 'Moderate risk';
        return 'Low risk';
    }

    function renderRiskBadge(risk) {
        return '<span class="risk-badge ' + getRiskBadgeClass(risk) + '">' + escapeHtml(formatRiskLabel(risk)) + '</span>';
    }

    function renderRiskList(risk, limit) {
        var items = risk && Array.isArray(risk.items) ? risk.items.slice(0, limit || 3) : [];
        if (items.length === 0) return '';
        return items.map(function(item) {
            return '<div class="risk-list-item">' +
                '<span class="risk-list-title">' + escapeHtml(item.title) + '</span>' +
                '<span class="risk-list-detail">' + escapeHtml(item.detail) + '</span>' +
            '</div>';
        }).join('');
    }

    function escapeJs(str) {
        if (!str) return '';
        return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    }

    function escapeCss(str) {
        if (!str) return '';
        if (window.CSS && typeof window.CSS.escape === 'function') {
            return window.CSS.escape(str);
        }
        return String(str).replace(/["\\]/g, '\\$&');
    }

    function getPreferredMigrationExtension() {
        var versions = state.schemaVersions || [];
        for (var index = 0; index < versions.length; index += 1) {
            var file = versions[index] && versions[index].migrationFile;
            if (!file || !file.fileName) continue;
            var parts = file.fileName.split('.');
            if (parts.length < 2) continue;
            var extension = parts[parts.length - 1].toLowerCase();
            if (extension === 'sql' || extension === 'sqm') {
                return extension;
            }
        }
        return 'sql';
    }

    function getNextMigrationVersion() {
        var versions = state.schemaVersions || [];
        var maxVersion = 0;
        versions.forEach(function(versionEntry) {
            if (versionEntry && versionEntry.migrationFile != null && typeof versionEntry.version === 'number') {
                maxVersion = Math.max(maxVersion, versionEntry.version);
            }
        });
        return maxVersion + 1;
    }

    function inferMigrationNameFromSql(sql) {
        if (!sql) return 'migration';
        var tableMatch = sql.match(/\b(?:CREATE|ALTER|DROP)\s+TABLE\s+(?:IF\s+(?:NOT\s+EXISTS|EXISTS)\s+)?(?:ONLY\s+)?([`"\[]?[A-Za-z_][\w$.\]]*)/i);
        if (tableMatch && tableMatch[1]) {
            return tableMatch[1].replace(/[`"\[\]]/g, '').split('.').pop() + '_changes';
        }
        var indexMatch = sql.match(/\bCREATE\s+(?:UNIQUE\s+)?INDEX\s+([A-Za-z_][\w$]*)/i);
        if (indexMatch && indexMatch[1]) {
            return indexMatch[1];
        }
        return 'migration';
    }

    function slugifyMigrationName(value) {
        return String(value || '')
            .trim()
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '');
    }

    function buildSuggestedMigrationFileName(options) {
        var opts = options || {};
        var version = Number.isFinite(Number(opts.version)) ? Number(opts.version) : getNextMigrationVersion();
        var extension = String(opts.extension || getPreferredMigrationExtension()).replace(/^\./, '').toLowerCase() || 'sql';
        var baseName = opts.explicitName || opts.name || inferMigrationNameFromSql(opts.sql || '');
        var safeName = slugifyMigrationName(baseName) || 'migration';
        var pattern = (state.settings && state.settings.migrationFileNamePattern) || '{version}';
        var now = new Date();
        var timestamp = [
            now.getFullYear(),
            String(now.getMonth() + 1).padStart(2, '0'),
            String(now.getDate()).padStart(2, '0'),
            String(now.getHours()).padStart(2, '0'),
            String(now.getMinutes()).padStart(2, '0'),
            String(now.getSeconds()).padStart(2, '0')
        ].join('');

        var fileName = String(pattern)
            .replace(/\{version\}/g, String(version))
            .replace(/\{name\}/g, safeName)
            .replace(/\{timestamp\}/g, timestamp)
            .replace(/\{extension\}/g, extension)
            .replace(/[\\/]+/g, '_')
            .replace(/\s+/g, '_')
            .replace(/^[_\.]+|[_\.]+$/g, '');

        if (!fileName) {
            fileName = String(version);
        }
        if (fileName.indexOf('.') === -1) {
            fileName += '.' + extension;
        } else if (String(pattern).indexOf('{extension}') === -1) {
            fileName += '.' + extension;
        }

        return fileName;
    }

    // Export escape helpers
    window.escapeHtml = escapeHtml;
    window.escapeJs = escapeJs;
    window.escapeCss = escapeCss;

    // Close export dropdown and pro popover when clicking outside
    document.addEventListener('click', function(e) {
        if (!e.target.closest('.export-dropdown-container')) {
            var dd = document.getElementById('export-dropdown');
            if (dd) dd.classList.remove('visible');
        }
    });

    console.log('[App] SQL Migration Visualizer initialized');
})();
