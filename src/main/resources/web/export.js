/**
 * SQL Migration Visualizer — Export Module
 *
 * Provides export functionality for ER diagrams (PNG) and schema data (SQL/JSON).
 */
(function() {
    'use strict';

    window.ExportModule = {

        /**
         * Export the ER diagram canvas as PNG via save dialog.
         */
        exportErAsPng: function() {
            var canvas = document.getElementById('er-canvas');
            if (!canvas || !window.__bridge) return;

            // Create a white-background copy for export
            var exportCanvas = document.createElement('canvas');
            exportCanvas.width = canvas.width;
            exportCanvas.height = canvas.height;
            var ctx = exportCanvas.getContext('2d');

            // Fill background
            var isDark = document.documentElement.getAttribute('data-theme') !== 'light';
            ctx.fillStyle = isDark ? '#1E1F22' : '#F7F8FA';
            ctx.fillRect(0, 0, exportCanvas.width, exportCanvas.height);

            // Draw original canvas on top
            ctx.drawImage(canvas, 0, 0);

            var dataUrl = exportCanvas.toDataURL('image/png');
            var base64 = dataUrl.replace('data:image/png;base64,', '');
            window.__bridge.saveFile(JSON.stringify({
                fileName: 'er-diagram.png',
                content: base64,
                encoding: 'base64'
            }));
        },

        /**
         * Export schema at a given version as JSON.
         */
        exportSchemaAsJson: function() {
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            if (!state || !state.schemaVersions || !window.__bridge) return;

            var jsonStr = JSON.stringify(state.schemaVersions, null, 2);
            window.__bridge.saveFile(JSON.stringify({
                fileName: 'schema-versions.json',
                content: jsonStr,
                encoding: 'utf8'
            }));
        },

        /**
         * Export the current schema version as CREATE TABLE SQL statements.
         */
        exportSchemaAsSql: function(versionNum) {
            var state = window.AppHelpers ? window.AppHelpers.getState() : null;
            if (!state || !state.schemaVersions || !window.__bridge) return;

            var version;
            if (versionNum !== undefined) {
                version = state.schemaVersions.find(function(v) { return v.version === versionNum; });
            } else {
                version = state.schemaVersions[state.schemaVersions.length - 1];
            }

            if (!version) return;

            var sql = '-- Schema at version ' + version.version + '\n\n';
            var tableNames = Object.keys(version.tables).sort();

            for (var i = 0; i < tableNames.length; i++) {
                var name = tableNames[i];
                var table = version.tables[name];

                sql += 'CREATE TABLE ' + name + ' (\n';
                var lines = [];

                for (var j = 0; j < table.columns.length; j++) {
                    var col = table.columns[j];
                    var parts = '    ' + col.name + ' ' + col.type;
                    if (col.isPrimaryKey) parts += ' PRIMARY KEY';
                    if (!col.nullable && !col.isPrimaryKey) parts += ' NOT NULL';
                    if (col.defaultValue) parts += ' DEFAULT ' + col.defaultValue;
                    lines.push(parts);
                }

                for (var k = 0; k < table.foreignKeys.length; k++) {
                    var fk = table.foreignKeys[k];
                    lines.push('    FOREIGN KEY (' + fk.columns.join(', ') + ') REFERENCES ' + fk.referencedTable + '(' + fk.referencedColumns.join(', ') + ')');
                }

                sql += lines.join(',\n');
                sql += '\n);\n\n';
            }

            window.__bridge.saveFile(JSON.stringify({
                fileName: 'schema-v' + version.version + '.sql',
                content: sql,
                encoding: 'utf8'
            }));
        }
    };
})();
