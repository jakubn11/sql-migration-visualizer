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
        metadataInputsBound: false,

        normalizeVersionValue: function(value) {
            var clean = String(value || '').replace(/\D/g, '').slice(0, 9);
            clean = clean.replace(/^0+/, '');
            return clean || '0';
        },

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

            versionInput.value = opts.version != null ? opts.version : defaults.version;
            dirInput.value = opts.directory || defaults.directory;
            nameInput.value = opts.name || defaults.name;
            modal.dataset.migrationExtension = opts.extension || defaults.extension || 'sql';
            modal.dataset.mode = isEditMode ? 'edit' : 'create';
            modal.dataset.filePath = opts.filePath || '';
            modal._composerOptions = opts;

            // Clear SQL and errors
            sqlInput.value = opts.sql || '';
            errorEl.style.display = 'none';
            errorEl.textContent = '';
            this.setSubmitting(false);
            this.clearValidationState();
            this.syncSqlHighlight(true);
            this.hideSuggestions();
            this.bindMetadataInputs();

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
                submitButton.textContent = opts.submitLabel
                    ? opts.submitLabel
                    : isEditMode
                        ? 'Save Changes'
                        : opts.sql
                            ? 'Create Suggested Migration'
                            : 'Create File';
                delete submitButton.dataset.defaultHtml;
            }

            this.renderContext();
            this.resizeVersionInput();
            this.growSqlEditor();
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

        bindMetadataInputs: function() {
            if (this.metadataInputsBound) return;
            this.metadataInputsBound = true;

            ['create-mig-version', 'create-mig-directory', 'create-mig-name'].forEach(function(id) {
                var input = document.getElementById(id);
                if (!input) return;
                if (id === 'create-mig-version') {
                    input.addEventListener('keydown', function(event) {
                        var allowed = ['Backspace', 'Delete', 'ArrowLeft', 'ArrowRight', 'Tab', 'Home', 'End'];
                        if (allowed.indexOf(event.key) === -1 && !/^[0-9]$/.test(event.key)) {
                            event.preventDefault();
                        }
                    });
                    input.addEventListener('input', function() {
                        var clean = CreateMigrationModule.normalizeVersionValue(input.value);
                        if (input.value !== clean) input.value = clean;
                        CreateMigrationModule.clearValidationState(input);
                        CreateMigrationModule.resizeVersionInput();
                        CreateMigrationModule.renderContext();
                    });
                } else {
                    input.addEventListener('input', function() {
                        CreateMigrationModule.clearValidationState(input);
                        CreateMigrationModule.renderContext();
                    });
                }
                input.addEventListener('change', function() {
                    if (id === 'create-mig-version') {
                        input.value = CreateMigrationModule.normalizeVersionValue(input.value);
                        CreateMigrationModule.resizeVersionInput();
                    }
                    CreateMigrationModule.renderContext();
                });
            });
        },

        renderContext: function() {},

        _lastHighlightedSql: null,
        _highlightScrollFrame: null,
        _dialogScrollFrame: null,

        syncSqlHighlight: function(force) {
            var sqlInput = document.getElementById('create-mig-sql');
            var highlightEl = document.getElementById('create-mig-sql-highlight');
            if (!sqlInput || !highlightEl) return;

            var sql = sqlInput.value || '';
            if (force || sql !== this._lastHighlightedSql) {
                var highlighted = window.SqlHighlighter && window.SqlHighlighter.highlight
                    ? window.SqlHighlighter.highlight(sql)
                    : sql
                        .replace(/&/g, '&amp;')
                        .replace(/</g, '&lt;')
                        .replace(/>/g, '&gt;');

                highlightEl.innerHTML = highlighted + (sql.endsWith('\n') ? '\n ' : '\n');
                this._lastHighlightedSql = sql;
            }

            this.syncSqlHighlightScroll(sqlInput, highlightEl);
        },

        syncSqlHighlightScroll: function(sqlInput, highlightEl) {
            sqlInput = sqlInput || document.getElementById('create-mig-sql');
            highlightEl = highlightEl || document.getElementById('create-mig-sql-highlight');
            if (!sqlInput || !highlightEl) return;

            highlightEl.scrollTop = sqlInput.scrollTop;
            highlightEl.scrollLeft = sqlInput.scrollLeft;
        },

        scheduleSqlHighlightScrollSync: function() {
            if (this._highlightScrollFrame !== null) return;

            var self = this;
            this._highlightScrollFrame = window.requestAnimationFrame(function() {
                self._highlightScrollFrame = null;
                self.syncSqlHighlightScroll();
            });
        },

        bindSqlEditor: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            if (!sqlInput || sqlInput.dataset.sqlEditorBound === 'true') return;

            sqlInput.dataset.sqlEditorBound = 'true';
            sqlInput.addEventListener('input', function() {
                CreateMigrationModule.syncSqlHighlight();
                CreateMigrationModule.growSqlEditor();
                CreateMigrationModule.clearValidationState(sqlInput);
                CreateMigrationModule.updateSuggestions();
                CreateMigrationModule.renderContext();
                CreateMigrationModule.scheduleSqlCaretIntoView();
            });
            sqlInput.addEventListener('scroll', function() {
                CreateMigrationModule.scheduleSqlHighlightScrollSync();
            }, { passive: true });
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
                if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                    event.preventDefault();
                    CreateMigrationModule.hideSuggestions();
                    CreateMigrationModule.submit();
                    return;
                }

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

            if (context.fragment.length < 2) {
                this.hideSuggestions();
                return;
            }

            var items = this.buildSuggestions(context).slice(0, 5);
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

        _caretMirror: null,
        _caretText: null,
        _caretSpan: null,
        _heightMirror: null,

        _ensureHeightMirror: function(sqlInput) {
            if (this._heightMirror) return;
            var mirror = document.createElement('div');
            var style = window.getComputedStyle(sqlInput);
            ['fontFamily', 'fontSize', 'lineHeight', 'padding', 'paddingTop', 'paddingBottom',
             'paddingLeft', 'paddingRight', 'boxSizing', 'whiteSpace', 'wordBreak', 'tabSize'].forEach(function(p) {
                mirror.style[p] = style[p];
            });
            mirror.style.position = 'absolute';
            mirror.style.visibility = 'hidden';
            mirror.style.top = '0';
            mirror.style.left = '-9999px';
            mirror.style.overflow = 'hidden';
            document.body.appendChild(mirror);
            this._heightMirror = mirror;
        },

        _ensureCaretMirror: function(sqlInput) {
            if (this._caretMirror) return;
            var mirror = document.createElement('div');
            var style = window.getComputedStyle(sqlInput);
            ['fontFamily', 'fontSize', 'lineHeight', 'padding', 'border', 'boxSizing', 'whiteSpace', 'wordBreak', 'tabSize'].forEach(function(p) {
                mirror.style[p] = style[p];
            });
            mirror.style.position = 'absolute';
            mirror.style.visibility = 'hidden';
            mirror.style.top = '0';
            mirror.style.left = '-9999px';
            mirror.style.height = 'auto';
            mirror.style.overflow = 'hidden';
            var text = document.createTextNode('');
            var span = document.createElement('span');
            span.textContent = '|';
            mirror.appendChild(text);
            mirror.appendChild(span);
            document.body.appendChild(mirror);
            this._caretMirror = mirror;
            this._caretText = text;
            this._caretSpan = span;
        },

        getCaretTopInEditor: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            if (!sqlInput) return null;
            this._ensureCaretMirror(sqlInput);
            var mirror = this._caretMirror;
            mirror.style.width = sqlInput.offsetWidth + 'px';
            var textBeforeCaret = sqlInput.value.substring(0, sqlInput.selectionStart);
            this._caretText.textContent = textBeforeCaret;
            this._caretSpan.textContent = '|';
            var caretTop = this._caretSpan.offsetTop - sqlInput.scrollTop;
            var lineHeight = parseInt(window.getComputedStyle(sqlInput).lineHeight) || 19;
            return { top: caretTop, lineHeight: lineHeight };
        },

        scheduleSqlCaretIntoView: function() {
            if (this._dialogScrollFrame !== null) return;

            var self = this;
            this._dialogScrollFrame = window.requestAnimationFrame(function() {
                self._dialogScrollFrame = null;
                self.scrollDialogToSqlCaret();
            });
        },

        scrollDialogToSqlCaret: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            var modalBody = document.querySelector('#create-migration-modal .modal-body');
            if (!sqlInput || !modalBody) return;

            var caret = this.getCaretTopInEditor();
            if (!caret) return;

            var inputRect = sqlInput.getBoundingClientRect();
            var bodyRect = modalBody.getBoundingClientRect();
            var edgePadding = 24;
            var caretTop = inputRect.top + caret.top;
            var caretBottom = caretTop + caret.lineHeight;
            var visibleTop = bodyRect.top + edgePadding;
            var visibleBottom = bodyRect.bottom - edgePadding;

            if (caretBottom > visibleBottom) {
                modalBody.scrollTop += caretBottom - visibleBottom;
            } else if (caretTop < visibleTop) {
                modalBody.scrollTop -= visibleTop - caretTop;
            }
        },

        growSqlEditor: function() {
            var sqlInput = document.getElementById('create-mig-sql');
            if (!sqlInput) return;
            this._ensureHeightMirror(sqlInput);
            var mirror = this._heightMirror;
            mirror.style.width = sqlInput.offsetWidth + 'px';
            var val = sqlInput.value;
            mirror.textContent = val.endsWith('\n') ? val + ' ' : val;
            sqlInput.style.height = Math.max(180, mirror.offsetHeight) + 'px';
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

            var caret = this.getCaretTopInEditor();
            if (caret) {
                var editorEl = suggestionsEl.parentElement;
                var editorHeight = editorEl ? editorEl.offsetHeight : 300;
                var dropdownHeight = suggestionsEl.offsetHeight || 142;
                var spaceBelow = editorHeight - (caret.top + caret.lineHeight);
                if (spaceBelow >= dropdownHeight + 8 || caret.top < dropdownHeight + 8) {
                    suggestionsEl.style.top = (caret.top + caret.lineHeight + 4) + 'px';
                    suggestionsEl.style.bottom = '';
                } else {
                    suggestionsEl.style.bottom = (editorHeight - caret.top + 4) + 'px';
                    suggestionsEl.style.top = '';
                }
            }

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
            this.growSqlEditor();
            this.scheduleSqlCaretIntoView();
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

        clearValidationState: function(field) {
            var fields = field
                ? [field]
                : [
                    document.getElementById('create-mig-version'),
                    document.getElementById('create-mig-directory'),
                    document.getElementById('create-mig-name'),
                    document.getElementById('create-mig-sql')
                ];

            fields.forEach(function(input) {
                if (!input) return;
                input.classList.remove('is-invalid');
                input.setAttribute('aria-invalid', 'false');
                var editor = input.closest ? input.closest('.sql-editor') : null;
                if (editor) {
                    editor.classList.remove('is-invalid');
                }
            });
            var modal = document.getElementById('create-migration-modal');
            var errorEl = document.getElementById('create-mig-error');
            if (errorEl && (!modal || !modal.querySelector('.is-invalid'))) {
                errorEl.textContent = '';
                errorEl.style.display = 'none';
            }
        },

        showValidationError: function(message, field) {
            var errorEl = document.getElementById('create-mig-error');
            if (errorEl) {
                errorEl.textContent = message;
                errorEl.style.display = 'block';
            }

            if (field) {
                field.classList.add('is-invalid');
                field.setAttribute('aria-invalid', 'true');
                var editor = field.closest ? field.closest('.sql-editor') : null;
                if (editor) {
                    editor.classList.add('is-invalid');
                }
                field.focus();
                if (field.select && field.tagName !== 'TEXTAREA') {
                    field.select();
                }
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

            this.clearValidationState();
            var rawVersion = this.normalizeVersionValue(versionInput.value);
            if (versionInput.value !== rawVersion) {
                versionInput.value = rawVersion;
                this.resizeVersionInput();
            }
            var version = parseInt(rawVersion, 10);
            var directory = dirInput.value.trim();
            var name = nameInput.value.trim();
            var sql = sqlInput.value.trim();
            var pattern = window.AppHelpers && window.AppHelpers.getState
                ? ((window.AppHelpers.getState().settings || {}).migrationFileNamePattern || '{version}')
                : '{version}';
            var patternUsesName = pattern.indexOf('{name}') !== -1;

            if (isNaN(version) || version < 0) {
                this.showValidationError('Version must be zero or greater.', versionInput);
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
                this.showValidationError('Version ' + version + ' already exists. Choose a different version number.', versionInput);
                return null;
            }

            // Validate directory
            if (!directory) {
                this.showValidationError('Migration directory is required.', dirInput);
                return null;
            }

            // Validate SQL
            if (!sql) {
                this.showValidationError('SQL statements cannot be empty.', sqlInput);
                return null;
            }

            if (patternUsesName && !name) {
                this.showValidationError('Migration name is required for the current naming pattern.', nameInput);
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

        resizeVersionInput: function() {
            var versionInput = document.getElementById('create-mig-version');
            if (!versionInput) return;
            var digits = String(versionInput.value || '0').replace(/\D/g, '').length || 1;
            var width = Math.min(76, Math.max(44, digits * 11 + 22));
            versionInput.style.width = width + 'px';
        },

        stepVersion: function(delta) {
            var versionInput = document.getElementById('create-mig-version');
            if (!versionInput) return;

            var currentValue = parseInt(versionInput.value, 10);
            if (isNaN(currentValue) || currentValue < 0) {
                currentValue = 0;
            }

            var nextValue = Math.max(0, currentValue + delta);
            versionInput.value = nextValue;
            versionInput.dispatchEvent(new Event('input', { bubbles: true }));
            this.resizeVersionInput();
            versionInput.focus();
        }
    };

    CreateMigrationModule.bindSqlEditor();

    // Callback when directory is selected from native dialog
    window.__onDirectorySelected = function(dir) {
        if (dir) {
            var directoryInput = document.getElementById('create-mig-directory');
            directoryInput.value = dir;
            window.__defaultMigrationDir = dir;
            if (window.CreateMigrationModule) {
                window.CreateMigrationModule.clearValidationState(directoryInput);
                window.CreateMigrationModule.renderContext();
            }
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
