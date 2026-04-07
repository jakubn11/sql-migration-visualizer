/**
 * SQL Migration Visualizer — ER Diagram Module
 *
 * Canvas-based entity-relationship diagram showing tables as cards
 * with columns, and foreign key relationships as connecting lines.
 * Supports pan, zoom, and hover highlighting.
 */
(function() {
    'use strict';

    const ERDiagramModule = {
        MIN_ZOOM: 0.3,
        MAX_ZOOM: 3,
        ZOOM_STEP: 1.2,

        // State
        canvas: null,
        ctx: null,
        tables: [],
        relationships: [],
        pan: { x: 0, y: 0 },
        zoom: 1,
        dragging: false,
        draggingTable: null,
        dragStart: { x: 0, y: 0 },
        tableDragOffset: { x: 0, y: 0 },
        hoveredTable: null,
        focusedTable: null,
        animFrame: null,
        tablePositionsByVersion: {},
        currentVersion: null,
        resizeHandler: null,

        // Design tokens (read dynamically from CSS)
        colors: {},

        getThemeColors: function() {
            var s = getComputedStyle(document.documentElement);
            var g = function(v) { return s.getPropertyValue(v).trim(); };
            return {
                bg: g('--bg-primary') || '#1E1F22',
                cardBg: g('--bg-secondary') || '#2B2D30',
                cardBorder: g('--border-default') || '#393B40',
                cardBorderHover: g('--accent-primary') || '#4D78CC',
                cardHeader: g('--bg-tertiary') || '#313335',
                text: g('--text-primary') || '#BCBEC4',
                textSecondary: g('--text-secondary') || '#8C8E94',
                textMuted: g('--text-muted') || '#6F737A',
                accent: g('--accent-primary') || '#4D78CC',
                type: g('--sql-type') || '#4EC9B0',
                pk: g('--color-warning') || '#E8A838',
                fk: g('--accent-secondary') || '#6C5CE7',
                fkLine: g('--accent-secondary') || '#6C5CE7',
                fkLineHover: document.documentElement.getAttribute('data-theme') === 'light'
                    ? '#7C3AED' : '#C4B5FD',
                shadow: 'rgba(0,0,0,0.3)',
                gridDot: document.documentElement.getAttribute('data-theme') === 'light'
                    ? 'rgba(124,58,237,0.04)' : 'rgba(167,139,250,0.04)'
            };
        },

        render: function(state) {
            const versions = state.schemaVersions;
            this.canvas = document.getElementById('er-canvas');
            this.ctx = this.canvas.getContext('2d');

            if (!versions || versions.length === 0) return;
            this.loadPersistedPositions(state);

            const versionNum = parseInt(document.getElementById('er-version').value);
            const schema = versions.find(v => v.version === versionNum);
            if (!schema) return;
            this.currentVersion = String(versionNum);
            this.focusedTable = state.ui && state.ui.erFocusTable ? state.ui.erFocusTable : null;

            // Setup canvas
            this.colors = this.getThemeColors();
            this.resizeCanvas();
            this.setupEvents();

            // Layout tables
            this.layoutTables(schema, versionNum);

            // Find relationships
            this.findRelationships(schema);

            // Draw
            if (this.focusedTable) {
                this.focusTable(this.focusedTable);
            } else {
                this.draw();
            }
        },

        resizeCanvas: function() {
            const parent = this.canvas.parentElement;
            const rect = parent.getBoundingClientRect();
            const dpr = window.devicePixelRatio || 1;

            this.ctx.setTransform(1, 0, 0, 1, 0, 0);
            this.canvas.width = rect.width * dpr;
            this.canvas.height = rect.height * dpr;
            this.canvas.style.width = rect.width + 'px';
            this.canvas.style.height = rect.height + 'px';
            this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            this.canvasWidth = rect.width;
            this.canvasHeight = rect.height;
        },

        clampZoom: function(zoom) {
            return Math.max(this.MIN_ZOOM, Math.min(this.MAX_ZOOM, zoom));
        },

        updateZoomDisplay: function() {
            const zoomLabel = document.getElementById('er-zoom-level');
            if (zoomLabel) {
                zoomLabel.textContent = Math.round(this.zoom * 100) + '%';
            }
        },

        zoomToPoint: function(targetZoom, anchorX, anchorY) {
            const clampedZoom = this.clampZoom(targetZoom);
            if (clampedZoom === this.zoom) {
                this.updateZoomDisplay();
                return;
            }

            this.pan.x = anchorX - (anchorX - this.pan.x) * (clampedZoom / this.zoom);
            this.pan.y = anchorY - (anchorY - this.pan.y) * (clampedZoom / this.zoom);
            this.zoom = clampedZoom;

            this.updateZoomDisplay();
            this.draw();
        },

        zoomBy: function(factor, anchorX, anchorY) {
            const centerX = anchorX != null ? anchorX : this.canvasWidth / 2;
            const centerY = anchorY != null ? anchorY : this.canvasHeight / 2;
            this.zoomToPoint(this.zoom * factor, centerX, centerY);
        },

        setupEvents: function() {
            const canvas = this.canvas;
            const self = this;

            // Remove old listeners
            canvas.onmousedown = null;
            canvas.onmousemove = null;
            canvas.onmouseup = null;
            canvas.onwheel = null;

            // Pan
            canvas.onmousedown = function(e) {
                const pointer = self.getWorldPoint(e.offsetX, e.offsetY);
                const hitTable = self.findTableAt(pointer.x, pointer.y);
                if (hitTable) {
                    self.draggingTable = hitTable;
                    self.tableDragOffset = {
                        x: pointer.x - hitTable.x,
                        y: pointer.y - hitTable.y
                    };
                } else {
                    self.dragging = true;
                    self.dragStart = { x: e.clientX - self.pan.x, y: e.clientY - self.pan.y };
                }
                canvas.style.cursor = 'grabbing';
            };

            canvas.onmousemove = function(e) {
                if (self.draggingTable) {
                    const pointer = self.getWorldPoint(e.offsetX, e.offsetY);
                    self.draggingTable.x = pointer.x - self.tableDragOffset.x;
                    self.draggingTable.y = pointer.y - self.tableDragOffset.y;
                    self.persistTablePositions();
                    self.hoveredTable = self.draggingTable.name;
                    self.draw();
                } else if (self.dragging) {
                    self.pan.x = e.clientX - self.dragStart.x;
                    self.pan.y = e.clientY - self.dragStart.y;
                    self.draw();
                } else {
                    // Hover detection
                    const pointer = self.getWorldPoint(e.offsetX, e.offsetY);
                    const hoveredTable = self.findTableAt(pointer.x, pointer.y);
                    const hovered = hoveredTable ? hoveredTable.name : null;
                    if (hovered !== self.hoveredTable) {
                        self.hoveredTable = hovered;
                        canvas.style.cursor = hovered ? 'move' : 'grab';
                        self.draw();
                    }
                }
            };

            canvas.onmouseup = function() {
                var shouldSaveLayout = !!self.draggingTable;
                self.draggingTable = null;
                self.dragging = false;
                canvas.style.cursor = self.hoveredTable ? 'move' : 'grab';
                if (shouldSaveLayout) {
                    self.persistTablePositions(true);
                }
            };

            canvas.onmouseleave = function() {
                var shouldSaveLayout = !!self.draggingTable;
                self.draggingTable = null;
                self.dragging = false;
                canvas.style.cursor = 'grab';
                if (shouldSaveLayout) {
                    self.persistTablePositions(true);
                }
            };

            // Zoom
            canvas.onwheel = function(e) {
                e.preventDefault();
                const factor = e.deltaY > 0 ? 1 / self.ZOOM_STEP : self.ZOOM_STEP;
                self.zoomBy(factor, e.offsetX, e.offsetY);
            };

            const zoomInButton = document.getElementById('btn-er-zoom-in');
            const zoomOutButton = document.getElementById('btn-er-zoom-out');
            if (zoomInButton) {
                zoomInButton.onclick = function() {
                    self.zoomBy(self.ZOOM_STEP);
                };
            }
            if (zoomOutButton) {
                zoomOutButton.onclick = function() {
                    self.zoomBy(1 / self.ZOOM_STEP);
                };
            }

            // Fit to view button
            document.getElementById('btn-er-reset').onclick = function() {
                self.fitToView();
            };

            // Handle resize
            if (self.resizeHandler) {
                window.removeEventListener('resize', self.resizeHandler);
            }
            self.resizeHandler = function() {
                self.resizeCanvas();
                self.draw();
            };
            window.addEventListener('resize', self.resizeHandler);
        },

        layoutTables: function(schema, versionNum) {
            const tableNames = Object.keys(schema.tables).sort();
            this.tables = [];
            const versionKey = String(versionNum);
            const storedPositions = this.tablePositionsByVersion[versionKey] || {};
            let hasStoredPositions = false;

            // Card dimensions
            const cardWidth = 200;
            const headerHeight = 28;
            const rowHeight = 20;
            const padding = 12;
            const gap = 40;

            // Grid layout (use setting if provided, otherwise auto)
            var settingsState = window.AppHelpers ? window.AppHelpers.getState().settings : null;
            var configuredCols = settingsState ? settingsState.erLayoutColumns : 0;
            const cols = configuredCols > 0 ? configuredCols : Math.max(1, Math.ceil(Math.sqrt(tableNames.length)));
            const startX = 40;
            const startY = 40;

            tableNames.forEach((name, index) => {
                const table = schema.tables[name];
                const col = index % cols;
                const row = Math.floor(index / cols);

                const height = headerHeight + table.columns.length * rowHeight + padding;

                // Calculate x/y with gap accounting for varying heights
                const defaultX = startX + col * (cardWidth + gap);
                const defaultY = startY + row * (180 + gap);
                const saved = storedPositions[name];
                const x = saved ? saved.x : defaultX;
                const y = saved ? saved.y : defaultY;
                if (saved) hasStoredPositions = true;

                this.tables.push({
                    name: name,
                    columns: table.columns,
                    primaryKey: table.primaryKey,
                    foreignKeys: table.foreignKeys,
                    x: x,
                    y: y,
                    width: cardWidth,
                    height: height,
                    headerHeight: headerHeight,
                    rowHeight: rowHeight
                });
            });

            this.persistTablePositions();

            // Center the layout only when we are using the default arrangement
            if (!hasStoredPositions) {
                this.fitToView();
            }
        },

        findRelationships: function(schema) {
            this.relationships = [];

            for (const tableName of Object.keys(schema.tables)) {
                const table = schema.tables[tableName];
                for (const fk of table.foreignKeys) {
                    this.relationships.push({
                        from: tableName,
                        fromColumns: fk.columns,
                        to: fk.referencedTable,
                        toColumns: fk.referencedColumns
                    });
                }
            }
        },

        fitToView: function() {
            if (this.tables.length === 0) return;

            const minX = Math.min(...this.tables.map(t => t.x));
            const minY = Math.min(...this.tables.map(t => t.y));
            const maxX = Math.max(...this.tables.map(t => t.x + t.width));
            const maxY = Math.max(...this.tables.map(t => t.y + t.height));

            const contentWidth = maxX - minX + 80;
            const contentHeight = maxY - minY + 80;

            const scaleX = this.canvasWidth / contentWidth;
            const scaleY = this.canvasHeight / contentHeight;
            this.zoom = this.clampZoom(Math.min(scaleX, scaleY, 1.5));

            this.pan.x = (this.canvasWidth - contentWidth * this.zoom) / 2 - minX * this.zoom + 40 * this.zoom;
            this.pan.y = (this.canvasHeight - contentHeight * this.zoom) / 2 - minY * this.zoom + 40 * this.zoom;

            this.updateZoomDisplay();
            this.draw();
        },

        focusTable: function(tableName) {
            const target = this.tables.find(t => t.name === tableName);
            if (!target) {
                this.draw();
                return;
            }

            this.zoom = this.clampZoom(Math.max(this.zoom, 1));
            this.pan.x = this.canvasWidth / 2 - (target.x + target.width / 2) * this.zoom;
            this.pan.y = this.canvasHeight / 2 - (target.y + target.height / 2) * this.zoom;
            this.updateZoomDisplay();
            this.draw();
        },

        getWorldPoint: function(offsetX, offsetY) {
            return {
                x: (offsetX - this.pan.x) / this.zoom,
                y: (offsetY - this.pan.y) / this.zoom
            };
        },

        findTableAt: function(worldX, worldY) {
            for (let i = this.tables.length - 1; i >= 0; i--) {
                const table = this.tables[i];
                if (worldX >= table.x && worldX <= table.x + table.width &&
                    worldY >= table.y && worldY <= table.y + table.height) {
                    return table;
                }
            }
            return null;
        },

        loadPersistedPositions: function(state) {
            var settings = state && state.settings ? state.settings : null;
            var persisted = settings && settings.erTablePositions ? settings.erTablePositions : {};
            if (!persisted) return;

            for (const versionKey of Object.keys(persisted)) {
                if (!this.tablePositionsByVersion[versionKey]) {
                    this.tablePositionsByVersion[versionKey] = persisted[versionKey];
                }
            }
        },

        persistTablePositions: function(saveRemote) {
            if (!this.currentVersion) return;
            const positions = {};
            for (const table of this.tables) {
                positions[table.name] = { x: table.x, y: table.y };
            }
            this.tablePositionsByVersion[this.currentVersion] = positions;

            if (saveRemote && window.__bridge && window.__bridge.saveErLayout) {
                window.__bridge.saveErLayout(JSON.stringify({
                    version: this.currentVersion,
                    positions: positions
                }));
            }
        },

        draw: function() {
            const ctx = this.ctx;
            const c = this.colors;

            // Clear
            ctx.clearRect(0, 0, this.canvasWidth, this.canvasHeight);
            ctx.fillStyle = c.bg;
            ctx.fillRect(0, 0, this.canvasWidth, this.canvasHeight);

            // Draw grid dots (if enabled in settings)
            var settings = window.AppHelpers ? window.AppHelpers.getState().settings : null;
            if (!settings || settings.erShowGrid !== false) {
                this.drawGrid(ctx);
            }

            ctx.save();
            ctx.translate(this.pan.x, this.pan.y);
            ctx.scale(this.zoom, this.zoom);

            // Draw relationships (lines behind)
            this.drawRelationships(ctx);

            // Draw table cards
            for (const table of this.tables) {
                this.drawTable(ctx, table);
            }

            ctx.restore();
        },

        drawGrid: function(ctx) {
            const gridSize = 20 * this.zoom;
            const offsetX = this.pan.x % gridSize;
            const offsetY = this.pan.y % gridSize;

            ctx.fillStyle = this.colors.gridDot;
            for (let x = offsetX; x < this.canvasWidth; x += gridSize) {
                for (let y = offsetY; y < this.canvasHeight; y += gridSize) {
                    ctx.beginPath();
                    ctx.arc(x, y, 0.8, 0, Math.PI * 2);
                    ctx.fill();
                }
            }
        },

        drawTable: function(ctx, table) {
            const c = this.colors;
            const activeTable = this.hoveredTable || this.focusedTable;
            const isFocused = this.focusedTable === table.name;
            const isHovered = this.hoveredTable === table.name || isFocused;
            const isRelated = activeTable && this.relationships.some(
                r => (r.from === activeTable && r.to === table.name) ||
                     (r.to === activeTable && r.from === table.name) ||
                     r.from === table.name || r.to === table.name
            );

            const x = table.x;
            const y = table.y;
            const w = table.width;
            const h = table.height;
            const r = 6;

            // Shadow
            if (isHovered) {
                ctx.shadowColor = 'rgba(77, 120, 204, 0.4)';
                ctx.shadowBlur = 16;
                ctx.shadowOffsetY = 4;
            } else {
                ctx.shadowColor = c.shadow;
                ctx.shadowBlur = 8;
                ctx.shadowOffsetY = 2;
            }

            // Card background
            ctx.beginPath();
            ctx.moveTo(x + r, y);
            ctx.lineTo(x + w - r, y);
            ctx.arcTo(x + w, y, x + w, y + r, r);
            ctx.lineTo(x + w, y + h - r);
            ctx.arcTo(x + w, y + h, x + w - r, y + h, r);
            ctx.lineTo(x + r, y + h);
            ctx.arcTo(x, y + h, x, y + h - r, r);
            ctx.lineTo(x, y + r);
            ctx.arcTo(x, y, x + r, y, r);
            ctx.closePath();

            ctx.fillStyle = c.cardBg;
            ctx.fill();
            ctx.shadowColor = 'transparent';

            // Border
            ctx.strokeStyle = isHovered ? c.cardBorderHover : (isRelated && activeTable ? c.fkLineHover : c.cardBorder);
            ctx.lineWidth = isHovered ? 2 : 1;
            ctx.stroke();

            // Header
            ctx.save();
            ctx.beginPath();
            ctx.moveTo(x + r, y);
            ctx.lineTo(x + w - r, y);
            ctx.arcTo(x + w, y, x + w, y + r, r);
            ctx.lineTo(x + w, y + table.headerHeight);
            ctx.lineTo(x, y + table.headerHeight);
            ctx.lineTo(x, y + r);
            ctx.arcTo(x, y, x + r, y, r);
            ctx.closePath();
            ctx.fillStyle = c.cardHeader;
            ctx.fill();
            ctx.restore();

            // Header divider
            ctx.beginPath();
            ctx.moveTo(x, y + table.headerHeight);
            ctx.lineTo(x + w, y + table.headerHeight);
            ctx.strokeStyle = c.cardBorder;
            ctx.lineWidth = 1;
            ctx.stroke();

            // Table name
            ctx.font = 'bold 12px Inter, sans-serif';
            ctx.fillStyle = isHovered ? c.accent : c.text;
            ctx.textBaseline = 'middle';
            ctx.fillText(table.name, x + 10, y + table.headerHeight / 2);

            // Columns
            for (let i = 0; i < table.columns.length; i++) {
                const col = table.columns[i];
                const cy = y + table.headerHeight + i * table.rowHeight + table.rowHeight / 2;

                // Column name
                ctx.font = '11px "JetBrains Mono", monospace';
                ctx.fillStyle = c.text;
                ctx.textAlign = 'left';
                ctx.fillText(col.name, x + 10, cy);

                // Measure column name width for badge positioning
                var colNameWidth = ctx.measureText(col.name).width;
                var badgeX = x + 10 + colNameWidth + 8;

                // PK indicator
                var isPk = col.isPrimaryKey || (table.primaryKey && table.primaryKey.includes(col.name));
                if (isPk) {
                    ctx.font = 'bold 8px Inter, sans-serif';
                    ctx.fillStyle = c.pk;
                    ctx.textAlign = 'left';
                    ctx.fillText('PK', badgeX, cy);
                    badgeX += ctx.measureText('PK').width + 6;
                }

                // FK indicator
                const isFk = table.foreignKeys.some(fk => fk.columns.includes(col.name));
                if (isFk) {
                    ctx.font = 'bold 8px Inter, sans-serif';
                    ctx.fillStyle = c.fk;
                    ctx.textAlign = 'left';
                    ctx.fillText('FK', badgeX, cy);
                }

                // Column type (right-aligned)
                ctx.font = '11px "JetBrains Mono", monospace';
                ctx.fillStyle = c.type;
                ctx.textAlign = 'right';
                ctx.fillText(col.type, x + w - 10, cy);

                ctx.textAlign = 'left';
            }
        },

        drawRelationships: function(ctx) {
            const c = this.colors;
            const activeTable = this.hoveredTable || this.focusedTable;

            for (const rel of this.relationships) {
                const fromTable = this.tables.find(t => t.name === rel.from);
                const toTable = this.tables.find(t => t.name === rel.to);
                if (!fromTable || !toTable) continue;

                const isHighlighted = activeTable === rel.from || activeTable === rel.to;

                // Calculate connection points
                const fromCenterX = fromTable.x + fromTable.width / 2;
                const fromCenterY = fromTable.y + fromTable.height / 2;
                const toCenterX = toTable.x + toTable.width / 2;
                const toCenterY = toTable.y + toTable.height / 2;

                // Determine exit/entry sides
                let fromX, fromY, toX, toY;

                if (Math.abs(fromCenterX - toCenterX) > Math.abs(fromCenterY - toCenterY)) {
                    // Horizontal connection
                    if (fromCenterX < toCenterX) {
                        fromX = fromTable.x + fromTable.width;
                        toX = toTable.x;
                    } else {
                        fromX = fromTable.x;
                        toX = toTable.x + toTable.width;
                    }
                    fromY = fromCenterY;
                    toY = toCenterY;
                } else {
                    // Vertical connection
                    if (fromCenterY < toCenterY) {
                        fromY = fromTable.y + fromTable.height;
                        toY = toTable.y;
                    } else {
                        fromY = fromTable.y;
                        toY = toTable.y + toTable.height;
                    }
                    fromX = fromCenterX;
                    toX = toCenterX;
                }

                // Draw curved line
                ctx.beginPath();
                ctx.moveTo(fromX, fromY);

                const midX = (fromX + toX) / 2;
                const midY = (fromY + toY) / 2;

                if (Math.abs(fromX - toX) > Math.abs(fromY - toY)) {
                    ctx.bezierCurveTo(midX, fromY, midX, toY, toX, toY);
                } else {
                    ctx.bezierCurveTo(fromX, midY, toX, midY, toX, toY);
                }

                ctx.strokeStyle = isHighlighted ? c.fkLineHover : c.fkLine;
                ctx.lineWidth = isHighlighted ? 2.5 : 1.5;
                ctx.setLineDash(isHighlighted ? [] : [4, 4]);
                ctx.globalAlpha = isHighlighted ? 1 : 0.5;
                ctx.stroke();
                ctx.setLineDash([]);
                ctx.globalAlpha = 1;

                // Arrow at destination
                const arrowSize = 6;
                const angle = Math.atan2(toY - midY, toX - midX);
                ctx.beginPath();
                ctx.moveTo(toX, toY);
                ctx.lineTo(
                    toX - arrowSize * Math.cos(angle - Math.PI / 6),
                    toY - arrowSize * Math.sin(angle - Math.PI / 6)
                );
                ctx.moveTo(toX, toY);
                ctx.lineTo(
                    toX - arrowSize * Math.cos(angle + Math.PI / 6),
                    toY - arrowSize * Math.sin(angle + Math.PI / 6)
                );
                ctx.strokeStyle = isHighlighted ? c.fkLineHover : c.fkLine;
                ctx.lineWidth = isHighlighted ? 2.5 : 1.5;
                ctx.globalAlpha = isHighlighted ? 1 : 0.6;
                ctx.stroke();
                ctx.globalAlpha = 1;
            }
        }
    };

    window.ERDiagramModule = ERDiagramModule;
})();
