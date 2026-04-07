/**
 * SQL Migration Visualizer — Create Migration Module
 *
 * Provides a modal UI for creating new migration files directly
 * from within the plugin, without needing to manually create files.
 */
(function() {
    'use strict';

    var SQL_COMPLETION_KEYWORDS = [
        'CREATE', 'TABLE', 'ALTER', 'ADD', 'COLUMN', 'DROP', 'RENAME', 'TO',
        'PRIMARY KEY', 'FOREIGN KEY', 'REFERENCES', 'NOT NULL', 'DEFAULT',
        'UNIQUE', 'CHECK', 'CONSTRAINT', 'INDEX', 'DROP TABLE', 'INSERT INTO', 'VALUES',
        'UPDATE', 'SET', 'DELETE', 'DELETE FROM', 'SELECT', 'FROM', 'WHERE', 'JOIN',
        'LEFT JOIN', 'INNER JOIN', 'GROUP BY', 'ORDER BY', 'LIMIT', 'OFFSET',
        'BEGIN', 'COMMIT', 'ROLLBACK', 'IF EXISTS', 'IF NOT EXISTS'
    ];

    function escapeHtml(value) {
        return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    const CreateMigrationModule = {
        submitting: false,
        completionState: null,

        getSuggestedDefaults: function() {
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            var versions = state ? state.schemaVersions : [];
            var pattern = state && state.settings ? state.settings.migrationFileNamePattern || '{version}' : '{version}';
            var nextVersion = 1;
            if (versions.length > 0) {
                var maxVersion = Math.max.apply(null, versions
                    .filter(function(v) { return v.migrationFile != null; })
                    .map(function(v) { return v.version; })
                    .concat([0]));
                nextVersion = maxVersion + 1;
            }

            var directory = window.__defaultMigrationDir || '';
            if (!directory && versions.length > 0) {
                var withFile = versions.find(function(v) { return v.migrationFile != null; });
                if (withFile) {
                    var path = withFile.migrationFile.filePath;
                    directory = path.substring(0, path.lastIndexOf('/'));
                }
            }

            return {
                version: nextVersion,
                directory: directory,
                name: pattern.indexOf('{name}') !== -1 ? '' : '',
                extension: window.AppHelpers && window.AppHelpers.getPreferredMigrationExtension
                    ? window.AppHelpers.getPreferredMigrationExtension()
                    : 'sql'
            };
        },

        openModal: function(options) {
            var modal = document.getElementById('create-migration-modal');
            var versionInput = document.getElementById('create-mig-version');
            var dirInput = document.getElementById('create-mig-directory');
            var nameInput = document.getElementById('create-mig-name');
            var nameGroup = document.getElementById('create-mig-name-group');
            var sqlInput = document.getElementById('create-mig-sql');
            var errorEl = document.getElementById('create-mig-error');
            var modalTitle = document.querySelector('#create-migration-modal .modal-header h3');
            var submitButton = document.getElementById('create-mig-submit');
            var versionStepper = document.querySelector('#create-migration-modal .version-stepper');
            var opts = options || {};
            var defaults = this.getSuggestedDefaults();
            var isEditMode = opts.mode === 'edit';
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            var namingPattern = state && state.settings ? state.settings.migrationFileNamePattern || '{version}' : '{version}';
            var patternUsesName = namingPattern.indexOf('{name}') !== -1;

            versionInput.value = opts.version || defaults.version;
            dirInput.value = opts.directory || defaults.directory;
            nameInput.value = opts.name || defaults.name;
            modal.dataset.migrationExtension = opts.extension || defaults.extension || 'sql';
            modal.dataset.mode = isEditMode ? 'edit' : 'create';
            modal.dataset.filePath = opts.filePath || '';

            // Clear SQL and errors
            sqlInput.value = opts.sql || '';
            errorEl.style.display = 'none';
            errorEl.textContent = '';
            this.setSubmitting(false);
            this.syncSqlHighlight();
            this.hideSuggestions();

            versionInput.disabled = isEditMode;
            dirInput.disabled = isEditMode;
            nameInput.disabled = isEditMode;
            if (nameGroup) {
                nameGroup.style.display = isEditMode || patternUsesName ? '' : 'none';
            }
            if (versionStepper) {
                versionStepper.style.display = isEditMode ? 'none' : '';
            }
            dirInput.classList.toggle('form-input-clickable', !isEditMode);
            if (isEditMode) {
                dirInput.removeAttribute('onclick');
                dirInput.title = 'Directory is fixed for existing migration files';
            } else {
                dirInput.setAttribute('onclick', "window.CreateMigrationModule && window.CreateMigrationModule.browseDirectory()");
                dirInput.title = 'Click to choose directory';
            }

            if (modalTitle) {
                modalTitle.textContent = opts.title || (isEditMode ? 'Edit Migration' : (opts.sql ? 'Review Pending Migration' : 'Create New Migration'));
            }
            if (submitButton) {
                submitButton.innerHTML = opts.submitLabel
                    ? '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>' + opts.submitLabel
                    : isEditMode
                        ? '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-2 14H7v-2h8v2zm0-4H7v-2h8v2zm-1-6V4.5L18.5 8H14z" fill="currentColor"/></svg>Save Changes'
                    : opts.sql
                        ? '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 10H8v-2h5v2zm0 4H8v-2h5v2zm0-8V3.5L18.5 9H13z" fill="currentColor"/></svg>Create Suggested Migration'
                        : '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z" fill="currentColor"/></svg>Create File';
                delete submitButton.dataset.defaultHtml;
            }

            modal.style.display = 'flex';
            // Focus the SQL textarea after animation
            setTimeout(function() {
                if (opts.sql) {
                    sqlInput.focus();
                } else if (!isEditMode && !patternUsesName) {
                    sqlInput.focus();
                } else {
                    nameInput.focus();
                    nameInput.select();
                }
            }, 100);
        },

        closeModal: function() {
            this.setSubmitting(false);
            document.getElementById('create-migration-modal').style.display = 'none';
            this.hideSuggestions();
        },

        syncSqlHighlight: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            var highlightEl = document.getElementById('create-mig-sql-highlight');
            if (!sqlInput || !highlightEl) return;

            var sql = sqlInput.value || '';
            var highlighted = window.SqlHighlighter && window.SqlHighlighter.highlight
                ? window.SqlHighlighter.highlight(sql)
                : sql
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;');

            highlightEl.innerHTML = highlighted + (sql.endsWith('\n') ? '\n ' : '\n');
            highlightEl.scrollTop = sqlInput.scrollTop;
            highlightEl.scrollLeft = sqlInput.scrollLeft;
        },

        bindSqlEditor: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            if (!sqlInput || sqlInput.dataset.sqlEditorBound === 'true') return;

            sqlInput.dataset.sqlEditorBound = 'true';
            sqlInput.addEventListener('input', function() {
                CreateMigrationModule.syncSqlHighlight();
                CreateMigrationModule.updateSuggestions();
            });
            sqlInput.addEventListener('scroll', function() {
                CreateMigrationModule.syncSqlHighlight();
            });
            sqlInput.addEventListener('click', function() {
                CreateMigrationModule.updateSuggestions();
            });
            sqlInput.addEventListener('keyup', function(event) {
                if (event.key === 'ArrowUp' || event.key === 'ArrowDown' || event.key === 'Enter' || event.key === 'Tab' || event.key === 'Escape') {
                    return;
                }
                CreateMigrationModule.updateSuggestions();
            });
            sqlInput.addEventListener('keydown', function(event) {
                if (!CreateMigrationModule.completionState || !CreateMigrationModule.completionState.items.length) {
                    return;
                }

                if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    CreateMigrationModule.moveSuggestionSelection(1);
                    return;
                }

                if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    CreateMigrationModule.moveSuggestionSelection(-1);
                    return;
                }

                if (event.key === 'Enter' || event.key === 'Tab') {
                    event.preventDefault();
                    CreateMigrationModule.applySelectedSuggestion();
                    return;
                }

                if (event.key === 'Escape') {
                    event.preventDefault();
                    CreateMigrationModule.hideSuggestions();
                }
            });

            document.addEventListener('click', function(event) {
                var editor = event.target.closest('.sql-editor');
                if (!editor) {
                    CreateMigrationModule.hideSuggestions();
                }
            });
        },

        updateSuggestions: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            var suggestionsEl = document.getElementById('create-mig-sql-suggestions');
            if (!sqlInput || !suggestionsEl) return;

            var context = this.getCompletionContext(sqlInput.value || '', sqlInput.selectionStart || 0);
            if (!context || !context.fragment) {
                this.hideSuggestions();
                return;
            }

            var items = this.buildSuggestions(context).slice(0, 8);
            if (!items.length) {
                this.hideSuggestions();
                return;
            }

            this.completionState = {
                start: context.start,
                end: context.end,
                fragment: context.fragment,
                items: items,
                selectedIndex: 0
            };
            this.renderSuggestions();
        },

        getCompletionContext: function(text, caretPosition) {
            var beforeCaret = text.slice(0, caretPosition);
            var tokenMatch = beforeCaret.match(/([A-Za-z_][A-Za-z0-9_$]*)$/);
            if (!tokenMatch) {
                return null;
            }

            var fragment = tokenMatch[1];
            var start = beforeCaret.length - fragment.length;
            var beforeToken = beforeCaret.slice(0, start).trimEnd();
            var previousWordMatch = beforeToken.match(/([A-Za-z_][A-Za-z0-9_$]*)$/);

            return {
                fragment: fragment,
                start: start,
                end: caretPosition,
                previousWord: previousWordMatch ? previousWordMatch[1].toUpperCase() : ''
            };
        },

        buildSuggestions: function(context) {
            var latestSchema = this.getLatestSchema();
            var fragmentUpper = context.fragment.toUpperCase();
            var items = [];
            var seen = Object.create(null);
            var tableNames = latestSchema ? Object.keys(latestSchema.tables || {}) : [];
            var columnNames = [];

            tableNames.forEach(function(tableName) {
                var table = latestSchema.tables[tableName];
                (table.columns || []).forEach(function(column) {
                    columnNames.push(column.name);
                });
            });

            function addSuggestion(value, type, meta, boost) {
                var key = type + '::' + value.toLowerCase();
                if (seen[key]) return;
                if (value.toUpperCase().indexOf(fragmentUpper) !== 0) return;
                seen[key] = true;
                items.push({
                    value: value,
                    type: type,
                    meta: meta,
                    boost: boost || 0
                });
            }

            SQL_COMPLETION_KEYWORDS.forEach(function(keyword) {
                var keywordUpper = keyword.toUpperCase();
                var boost = 10;
                if (context.previousWord === 'ALTER' || context.previousWord === 'FROM' || context.previousWord === 'JOIN' || context.previousWord === 'TABLE') {
                    boost = keywordUpper.indexOf('TABLE') === 0 ? 40 : boost;
                }
                addSuggestion(keyword, 'keyword', '', boost);
            });

            tableNames.forEach(function(tableName) {
                var boost = 20;
                if (context.previousWord === 'FROM' || context.previousWord === 'JOIN' || context.previousWord === 'UPDATE' || context.previousWord === 'INTO' || context.previousWord === 'TABLE' || context.previousWord === 'ALTER') {
                    boost = 80;
                }
                addSuggestion(tableName, 'table', 'Latest schema table', boost);
            });

            Array.from(new Set(columnNames)).forEach(function(columnName) {
                var boost = 15;
                if (context.previousWord === 'SELECT' || context.previousWord === 'WHERE' || context.previousWord === 'SET' || context.previousWord === 'COLUMN') {
                    boost = 70;
                }
                addSuggestion(columnName, 'column', 'Latest schema column', boost);
            });

            return items.sort(function(a, b) {
                if (b.boost !== a.boost) return b.boost - a.boost;
                if (a.type !== b.type) return a.type.localeCompare(b.type);
                return a.value.localeCompare(b.value);
            });
        },

        getLatestSchema: function() {
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            var versions = state ? state.schemaVersions || [] : [];
            return versions.length ? versions[versions.length - 1] : null;
        },

        renderSuggestions: function() {
            var suggestionsEl = document.getElementById('create-mig-sql-suggestions');
            if (!suggestionsEl || !this.completionState) return;

            suggestionsEl.innerHTML = this.completionState.items.map(function(item, index) {
                var metaHtml = item.meta
                    ? '<div class="sql-suggestion-meta">' + escapeHtml(item.meta) + '</div>'
                    : '';
                return '' +
                    '<div class="sql-suggestion-item' + (index === CreateMigrationModule.completionState.selectedIndex ? ' is-selected' : '') + '" data-suggestion-index="' + index + '">' +
                    '  <div class="sql-suggestion-main">' +
                    '    <div class="sql-suggestion-value">' + escapeHtml(item.value) + '</div>' +
                         metaHtml +
                    '  </div>' +
                    '  <span class="sql-suggestion-type">' + escapeHtml(item.type) + '</span>' +
                    '</div>';
            }).join('');
            suggestionsEl.style.display = 'block';

            Array.prototype.forEach.call(suggestionsEl.querySelectorAll('.sql-suggestion-item'), function(itemEl) {
                itemEl.addEventListener('mousedown', function(event) {
                    event.preventDefault();
                });
                itemEl.addEventListener('click', function() {
                    var index = parseInt(itemEl.dataset.suggestionIndex, 10);
                    if (!Number.isNaN(index)) {
                        CreateMigrationModule.completionState.selectedIndex = index;
                        CreateMigrationModule.applySelectedSuggestion();
                    }
                });
            });
        },

        moveSuggestionSelection: function(delta) {
            if (!this.completionState || !this.completionState.items.length) return;
            var nextIndex = this.completionState.selectedIndex + delta;
            if (nextIndex < 0) {
                nextIndex = this.completionState.items.length - 1;
            } else if (nextIndex >= this.completionState.items.length) {
                nextIndex = 0;
            }
            this.completionState.selectedIndex = nextIndex;
            this.renderSuggestions();
        },

        applySelectedSuggestion: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            if (!sqlInput || !this.completionState || !this.completionState.items.length) return;

            var selected = this.completionState.items[this.completionState.selectedIndex];
            var insertValue = selected.value;
            var suffix = selected.type === 'keyword' ? ' ' : '';

            sqlInput.focus();
            sqlInput.setRangeText(insertValue + suffix, this.completionState.start, this.completionState.end, 'end');
            this.syncSqlHighlight();
            this.hideSuggestions();
        },

        hideSuggestions: function() {
            var suggestionsEl = document.getElementById('create-mig-sql-suggestions');
            if (suggestionsEl) {
                suggestionsEl.style.display = 'none';
                suggestionsEl.innerHTML = '';
            }
            this.completionState = null;
        },

        setSubmitting: function(isSubmitting) {
            this.submitting = isSubmitting;
            var createButton = document.getElementById('create-mig-submit');
            if (createButton) {
                if (!createButton.dataset.defaultHtml) {
                    createButton.dataset.defaultHtml = createButton.innerHTML;
                }
                createButton.disabled = isSubmitting;
                createButton.innerHTML = isSubmitting ? 'Creating...' : createButton.dataset.defaultHtml;
            }
        },

        validate: function() {
            var versionInput = document.getElementById('create-mig-version');
            var dirInput = document.getElementById('create-mig-directory');
            var nameInput = document.getElementById('create-mig-name');
            var sqlInput = document.getElementById('create-mig-sql');
            var errorEl = document.getElementById('create-mig-error');
            var modal = document.getElementById('create-migration-modal');
            var isEditMode = modal && modal.dataset.mode === 'edit';

            var version = parseInt(versionInput.value);
            var directory = dirInput.value.trim();
            var name = nameInput.value.trim();
            var sql = sqlInput.value.trim();
            var pattern = window.AppHelpers && window.AppHelpers.getState
                ? ((window.AppHelpers.getState().settings || {}).migrationFileNamePattern || '{version}')
                : '{version}';
            var patternUsesName = pattern.indexOf('{name}') !== -1;

            // Validate version
            if (isNaN(version) || version < 1) {
                errorEl.textContent = 'Version must be a positive number.';
                errorEl.style.display = 'block';
                return null;
            }

            // Check for duplicate version
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            var versions = state ? state.schemaVersions : [];
            var exists = versions.some(function(v) {
                if (v.migrationFile == null || v.version !== version) return false;
                if (!isEditMode) return true;
                return v.migrationFile.filePath !== modal.dataset.filePath;
            });
            if (exists) {
                errorEl.textContent = 'Version ' + version + ' already exists. Choose a different version number.';
                errorEl.style.display = 'block';
                return null;
            }

            // Validate directory
            if (!directory) {
                errorEl.textContent = 'Migration directory is required.';
                errorEl.style.display = 'block';
                return null;
            }

            // Validate SQL
            if (!sql) {
                errorEl.textContent = 'SQL content cannot be empty.';
                errorEl.style.display = 'block';
                return null;
            }

            if (patternUsesName && !name) {
                errorEl.textContent = 'Migration name is required for the current naming pattern.';
                errorEl.style.display = 'block';
                return null;
            }

            errorEl.style.display = 'none';
            var extension = modal && modal.dataset.migrationExtension ? modal.dataset.migrationExtension : 'sql';
            return {
                version: version,
                directory: directory,
                name: patternUsesName ? name : '',
                content: sql,
                extension: extension,
                mode: isEditMode ? 'edit' : 'create',
                filePath: modal && modal.dataset.filePath ? modal.dataset.filePath : ''
            };
        },

        submit: function() {
            if (this.submitting) return;

            var params = this.validate();
            if (!params) return;

            var modal = document.getElementById('create-migration-modal');
            var isEditMode = modal && modal.dataset.mode === 'edit';
            if (!window.__bridge || (isEditMode ? !window.__bridge.saveMigration : !window.__bridge.createMigration)) {
                console.error('[CreateMigration] Bridge not available');
                return;
            }

            this.setSubmitting(true);
            if (isEditMode) {
                window.__bridge.saveMigration(JSON.stringify({
                    filePath: params.filePath,
                    content: params.content,
                    openAfterSave: false
                }));
            } else {
                window.__bridge.createMigration(JSON.stringify(params));
            }
        },

        browseDirectory: function() {
            var modal = document.getElementById('create-migration-modal');
            if (modal && modal.dataset.mode === 'edit') return;
            if (window.__bridge && window.__bridge.browseDirectory) {
                var currentDir = document.getElementById('create-mig-directory').value;
                window.__bridge.browseDirectory(currentDir);
            }
        },

        stepVersion: function(delta) {
            var versionInput = document.getElementById('create-mig-version');
            if (!versionInput) return;

            var currentValue = parseInt(versionInput.value, 10);
            if (isNaN(currentValue) || currentValue < 1) {
                currentValue = 1;
            }

            var nextValue = Math.max(1, currentValue + delta);
            versionInput.value = nextValue;
            versionInput.dispatchEvent(new Event('input', { bubbles: true }));
            versionInput.focus();
        }
    };

    CreateMigrationModule.bindSqlEditor();

    // Callback when directory is selected from native dialog
    window.__onDirectorySelected = function(dir) {
        if (dir) {
            document.getElementById('create-mig-directory').value = dir;
            window.__defaultMigrationDir = dir;
        }
    };

    window.__onMigrationCreated = function(filePath) {
        if (window.CreateMigrationModule) {
            window.CreateMigrationModule.closeModal();
        }
        if (window.AppUi && window.AppUi.showToast) {
            var fileName = filePath ? filePath.split('/').pop() : 'Migration file';
            window.AppUi.showToast(fileName + ' created successfully.', 'success');
        }
    };

    window.__onMigrationSaved = function(filePath) {
        if (window.CreateMigrationModule) {
            window.CreateMigrationModule.closeModal();
        }
        if (window.AppUi && window.AppUi.showToast) {
            var fileName = filePath ? filePath.split('/').pop() : 'Migration file';
            window.AppUi.showToast(fileName + ' saved successfully.', 'success');
        }
    };

    window.__onCreateMigrationError = function(message) {
        if (!window.CreateMigrationModule) return;

        var errorEl = document.getElementById('create-mig-error');
        var modal = document.getElementById('create-migration-modal');
        var modalVisible = modal && modal.style.display !== 'none';
        if (errorEl) {
            errorEl.textContent = message || 'Failed to create migration file.';
            errorEl.style.display = modalVisible ? 'block' : 'none';
        }
        window.CreateMigrationModule.setSubmitting(false);
        if (!modalVisible && window.AppUi && window.AppUi.showToast) {
            window.AppUi.showToast(message || 'Failed to create migration file.', 'error');
        }
    };

    window.__onMigrationSaveError = function(message) {
        if (!window.CreateMigrationModule) return;

        var errorEl = document.getElementById('create-mig-error');
        var modal = document.getElementById('create-migration-modal');
        var modalVisible = modal && modal.style.display !== 'none';
        if (errorEl) {
            errorEl.textContent = message || 'Failed to save migration file.';
            errorEl.style.display = modalVisible ? 'block' : 'none';
        }
        window.CreateMigrationModule.setSubmitting(false);
        if (!modalVisible && window.AppUi && window.AppUi.showToast) {
            window.AppUi.showToast(message || 'Failed to save migration file.', 'error');
        }
    };

    window.CreateMigrationModule = CreateMigrationModule;
})();
