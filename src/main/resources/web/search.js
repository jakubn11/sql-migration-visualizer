/**
 * SQL Migration Visualizer — Search Module
 *
 * Provides search across all tables and columns in all schema versions.
 * Results support timeline, diff, and ER navigation with keyboard control.
 */
(function() {
    'use strict';

    var debounceTimer = null;
    var input = document.getElementById('search-input');
    var resultsEl = document.getElementById('search-results');
    var renderedResults = [];
    var selectedIndex = -1;

    if (!input || !resultsEl) return;

    input.addEventListener('input', function() {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(function() {
            performSearch(input.value.trim());
        }, 180);
    });

    input.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') {
            clearSearch();
            input.blur();
            return;
        }

        if (e.key === 'ArrowDown') {
            e.preventDefault();
            moveSelection(1);
            return;
        }

        if (e.key === 'ArrowUp') {
            e.preventDefault();
            moveSelection(-1);
            return;
        }

        if (e.key === 'Enter' && renderedResults.length > 0) {
            e.preventDefault();
            var target = renderedResults[Math.max(0, selectedIndex)];
            openResult(target, 'timeline');
        }
    });

    document.addEventListener('click', function(e) {
        if (!e.target.closest('.search-container')) {
            resultsEl.style.display = 'none';
        }
    });

    document.addEventListener('keydown', function(e) {
        if ((e.ctrlKey || e.metaKey) && e.key === 'f') {
            e.preventDefault();
            input.focus();
            input.select();
        }
    });

    function performSearch(query) {
        if (!query) {
            resultsEl.style.display = 'none';
            renderedResults = [];
            selectedIndex = -1;
            return;
        }

        var state = window.AppHelpers ? window.AppHelpers.getState() : null;
        if (!state || !state.schemaVersions || state.schemaVersions.length === 0) {
            resultsEl.style.display = 'none';
            return;
        }

        var lowerQuery = query.toLowerCase();
        var versionCount = state.schemaVersions.length;
        var matches = [];
        var seen = {};

        state.schemaVersions.forEach(function(version, versionIndex) {
            Object.keys(version.tables).forEach(function(tableName) {
                var table = version.tables[tableName];
                var tableRank = rankMatch(tableName, lowerQuery);
                if (tableRank >= 0) {
                    pushMatch({
                        kind: 'table',
                        tableName: tableName,
                        columnName: '',
                        version: version.version,
                        versionIndex: versionIndex,
                        rank: tableRank,
                        age: versionCount - versionIndex
                    });
                }

                table.columns.forEach(function(column) {
                    var columnRank = rankMatch(column.name, lowerQuery);
                    if (columnRank >= 0) {
                        pushMatch({
                            kind: 'column',
                            tableName: tableName,
                            columnName: column.name,
                            version: version.version,
                            versionIndex: versionIndex,
                            rank: columnRank,
                            age: versionCount - versionIndex
                        });
                    }
                });
            });
        });

        matches.sort(function(a, b) {
            if (a.kind !== b.kind) return a.kind === 'table' ? -1 : 1;
            if (a.rank !== b.rank) return a.rank - b.rank;
            if (a.age !== b.age) return a.age - b.age;
            if (a.tableName !== b.tableName) return a.tableName.localeCompare(b.tableName);
            return a.columnName.localeCompare(b.columnName);
        });

        var maxResults = (state.settings && state.settings.searchResultLimit) ? state.settings.searchResultLimit : 20;
        renderedResults = matches.slice(0, maxResults);
        selectedIndex = renderedResults.length > 0 ? 0 : -1;

        if (renderedResults.length === 0) {
            resultsEl.innerHTML = '<div class="search-no-results">No matches found</div>';
            resultsEl.style.display = 'block';
            return;
        }

        renderResults(lowerQuery, matches.length);
        resultsEl.style.display = 'block';

        function pushMatch(match) {
            var key = [
                match.kind,
                match.tableName,
                match.columnName,
                match.version
            ].join('|');
            if (seen[key]) return;
            seen[key] = true;
            matches.push(match);
        }
    }

    function renderResults(lowerQuery, totalCount) {
        var html = renderedResults.map(function(result, index) {
            var title = result.kind === 'table'
                ? highlightMatch(result.tableName, lowerQuery)
                : highlightMatch(result.tableName, lowerQuery) + '<span class="search-result-separator">.</span>' + highlightMatch(result.columnName, lowerQuery);
            var meta = result.kind === 'table'
                ? 'Table match'
                : 'Column match in ' + escapeHtml(result.tableName);

            return '<div class="search-result-item' + (index === selectedIndex ? ' is-selected' : '') + '" ' +
                'data-index="' + index + '">' +
                '<div class="search-result-body">' +
                    '<div class="search-result-heading">' +
                        '<span class="search-result-label">' + title + '</span>' +
                        '<span class="search-result-version">v' + result.version + '</span>' +
                    '</div>' +
                    '<div class="search-result-meta">' + escapeHtml(meta) + '</div>' +
                '</div>' +
                '<div class="search-result-actions">' +
                    '<button type="button" class="search-action-btn" data-view="timeline" data-index="' + index + '">Timeline</button>' +
                    '<button type="button" class="search-action-btn" data-view="diff" data-index="' + index + '">Diff</button>' +
                    '<button type="button" class="search-action-btn" data-view="er" data-index="' + index + '">ER</button>' +
                '</div>' +
            '</div>';
        }).join('');

        if (totalCount > renderedResults.length) {
            html += '<div class="search-no-results">Showing ' + renderedResults.length + ' of ' + totalCount + ' matches</div>';
        }

        resultsEl.innerHTML = html;

        resultsEl.querySelectorAll('.search-result-item').forEach(function(item) {
            item.addEventListener('mouseenter', function() {
                selectedIndex = parseInt(item.dataset.index, 10);
                syncSelection();
            });
            item.addEventListener('click', function() {
                var result = renderedResults[parseInt(item.dataset.index, 10)];
                openResult(result, 'timeline');
            });
        });

        resultsEl.querySelectorAll('.search-action-btn').forEach(function(button) {
            button.addEventListener('click', function(e) {
                e.stopPropagation();
                var result = renderedResults[parseInt(button.dataset.index, 10)];
                openResult(result, button.dataset.view);
            });
        });
    }

    function moveSelection(direction) {
        if (renderedResults.length === 0) return;
        selectedIndex = selectedIndex < 0
            ? 0
            : Math.max(0, Math.min(renderedResults.length - 1, selectedIndex + direction));
        syncSelection();
    }

    function syncSelection() {
        resultsEl.querySelectorAll('.search-result-item').forEach(function(item, index) {
            item.classList.toggle('is-selected', index === selectedIndex);
        });
    }

    function openResult(result, view) {
        if (!result || !window.AppActions || !window.AppActions.openSearchResult) return;
        window.AppActions.openSearchResult({
            tableName: result.tableName,
            columnName: result.columnName,
            version: result.version,
            view: view
        });
        clearSearch();
    }

    function clearSearch() {
        input.value = '';
        resultsEl.style.display = 'none';
        renderedResults = [];
        selectedIndex = -1;
    }

    function rankMatch(text, query) {
        var lower = text.toLowerCase();
        if (lower === query) return 0;
        if (lower.indexOf(query) === 0) return 1;
        if (lower.indexOf(query) !== -1) return 2;
        return -1;
    }

    function highlightMatch(text, query) {
        var idx = text.toLowerCase().indexOf(query);
        if (idx === -1) return escapeHtml(text);
        return escapeHtml(text.substring(0, idx))
            + '<strong>' + escapeHtml(text.substring(idx, idx + query.length)) + '</strong>'
            + escapeHtml(text.substring(idx + query.length));
    }

    window.SearchModule = {
        clearSearch: clearSearch
    };
})();
