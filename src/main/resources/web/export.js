/**
 * SQL Migration Visualizer — Export Module
 *
 * Provides export functionality for ER diagrams (PNG) and schema data (SQL/JSON).
 */
(function() {
    'use strict';

    function drawWatermark(ctx, canvasWidth, canvasHeight) {
        // The export canvas is in device pixels (it was copied from the
        // DPR-scaled ER canvas), so scale visual sizes by DPR to keep the
        // watermark looking right on hi-DPI screens.
        var dpr = window.devicePixelRatio || 1;
        var logoImg = document.querySelector('.logo-icon-image');
        var brand = 'SQL Migration Visualizer';

        var padding = 16 * dpr;
        var logoSize = 22 * dpr;
        var spacing = 8 * dpr;
        var fontSize = 12 * dpr;

        var fontFamily = getComputedStyle(document.documentElement)
            .getPropertyValue('--font-sans')
            .trim() || 'system-ui, -apple-system, sans-serif';

        ctx.save();
        ctx.font = '600 ' + fontSize + 'px ' + fontFamily;
        ctx.textBaseline = 'middle';
        var textWidth = ctx.measureText(brand).width;

        var totalWidth = logoSize + spacing + textWidth;
        var centerY = canvasHeight - padding - logoSize / 2;
        var startX = canvasWidth - padding - totalWidth;

        ctx.globalAlpha = 0.18;

        if (logoImg && logoImg.complete && logoImg.naturalWidth > 0) {
            try {
                // Recolor the logo to white while preserving its transparency.
                ctx.filter = 'brightness(0) invert(1)';
                ctx.drawImage(logoImg, startX, centerY - logoSize / 2, logoSize, logoSize);
                ctx.filter = 'none';
            } catch (e) { /* drawing the logo is best-effort */ }
        }

        ctx.fillStyle = '#FFFFFF';
        ctx.fillText(brand, startX + logoSize + spacing, centerY);
        ctx.restore();
    }

    window.ExportModule = {

        /**
         * Export the ER diagram canvas as PNG via save dialog.
         */
        exportErAsPng: function() {
            var canvas = document.getElementById('er-canvas');
            if (!canvas || !window.__bridge) return;

            var er = window.ERDiagramModule;
            var savedHovered = er ? er.hoveredTable : null;
            var savedFocused = er ? er.focusedTable : null;
            if (er) {
                // Re-render without hover or focus highlights so the export is clean.
                er.hoveredTable = null;
                er.focusedTable = null;
                er.draw();
            }

            try {
                var exportCanvas = document.createElement('canvas');
                exportCanvas.width = canvas.width;
                exportCanvas.height = canvas.height;
                var ctx = exportCanvas.getContext('2d');

                var isDark = document.documentElement.getAttribute('data-theme') !== 'light';
                ctx.fillStyle = isDark ? '#1E1F22' : '#F7F8FA';
                ctx.fillRect(0, 0, exportCanvas.width, exportCanvas.height);
                ctx.drawImage(canvas, 0, 0);

                drawWatermark(ctx, exportCanvas.width, exportCanvas.height);

                var dataUrl = exportCanvas.toDataURL('image/png');
                var base64 = dataUrl.replace('data:image/png;base64,', '');
                window.__bridge.saveFile(JSON.stringify({
                    fileName: 'er-diagram.png',
                    content: base64,
                    encoding: 'base64'
                }));
            } finally {
                if (er) {
                    er.hoveredTable = savedHovered;
                    er.focusedTable = savedFocused;
                    er.draw();
                }
            }
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
