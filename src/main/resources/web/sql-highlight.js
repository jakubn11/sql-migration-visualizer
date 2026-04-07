/**
 * SQL Migration Visualizer — SQL Syntax Highlighter
 *
 * Lightweight regex-based SQL tokenizer that returns HTML with
 * <span class="sql-*"> wrappers for syntax highlighting.
 */
(function() {
    'use strict';

    var SQL_KEYWORDS = /\b(CREATE|TABLE|ALTER|ADD|COLUMN|DROP|IF|NOT|EXISTS|NULL|DEFAULT|PRIMARY|KEY|FOREIGN|REFERENCES|INTEGER|TEXT|REAL|BLOB|RENAME|TO|UNIQUE|CHECK|CONSTRAINT|INSERT|UPDATE|DELETE|SELECT|FROM|WHERE|AND|OR|INDEX|ON|BEGIN|COMMIT|ROLLBACK|SET|VALUES|INTO|CASCADE|RESTRICT|NO|ACTION|AUTOINCREMENT|REPLACE|ABORT|FAIL|IGNORE|ASC|DESC|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|AS|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|NATURAL|USING|IN|BETWEEN|LIKE|GLOB|IS|CASE|WHEN|THEN|ELSE|END|CAST|WITH|RECURSIVE|VACUUM|REINDEX|ANALYZE|EXPLAIN|PRAGMA|ATTACH|DETACH|GRANT|REVOKE)\b/gi;

    var SQL_TYPES = /\b(INTEGER|TEXT|REAL|BLOB|NUMERIC|BOOLEAN|VARCHAR|BIGINT|SMALLINT|FLOAT|DOUBLE|DATE|TIMESTAMP|DATETIME|CHAR|DECIMAL|CLOB|NVARCHAR|NCHAR|INT)\b/gi;

    window.SqlHighlighter = {
        highlight: function(sql) {
            if (!sql) return '';

            // Escape HTML first
            var escaped = sql
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;');

            // Tokenize: protect strings and comments first
            var tokens = [];
            var result = escaped;

            // Replace block comments
            result = result.replace(/\/\*[\s\S]*?\*\//g, function(m) {
                return '<span class="sql-comment">' + m + '</span>';
            });

            // Replace line comments
            result = result.replace(/--.*/g, function(m) {
                return '<span class="sql-comment">' + m + '</span>';
            });

            // Replace strings (single-quoted)
            result = result.replace(/'([^']*(?:''[^']*)*)'/g, function(m) {
                return '<span class="sql-string">' + m + '</span>';
            });

            // Replace numbers (not inside existing spans)
            result = result.replace(/(?<![.\w<])(\b\d+\.?\d*\b)(?![^<]*<\/span>)/g, function(m) {
                return '<span class="sql-number">' + m + '</span>';
            });

            // Replace types (before keywords since some overlap)
            result = result.replace(/(?<![<\w])(\b(?:INTEGER|TEXT|REAL|BLOB|NUMERIC|BOOLEAN|VARCHAR|BIGINT|SMALLINT|FLOAT|DOUBLE|DATE|TIMESTAMP|DATETIME|CHAR|DECIMAL|CLOB|NVARCHAR|NCHAR|INT)\b)(?![^<]*<\/span>)/gi, function(m) {
                return '<span class="sql-type">' + m + '</span>';
            });

            // Replace keywords
            result = result.replace(/(?<![<\w])(\b(?:CREATE|TABLE|ALTER|ADD|COLUMN|DROP|IF|NOT|EXISTS|NULL|DEFAULT|PRIMARY|KEY|FOREIGN|REFERENCES|RENAME|TO|UNIQUE|CHECK|CONSTRAINT|INSERT|UPDATE|DELETE|SELECT|FROM|WHERE|AND|OR|INDEX|ON|BEGIN|COMMIT|ROLLBACK|SET|VALUES|INTO|CASCADE|RESTRICT|NO|ACTION|AUTOINCREMENT|REPLACE|ABORT|FAIL|IGNORE|ASC|DESC|ORDER|BY|GROUP|HAVING|LIMIT|OFFSET|UNION|ALL|DISTINCT|AS|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|NATURAL|USING|IN|BETWEEN|LIKE|GLOB|IS|CASE|WHEN|THEN|ELSE|END|CAST|WITH|RECURSIVE|VACUUM|REINDEX|ANALYZE|EXPLAIN|PRAGMA|ATTACH|DETACH)\b)(?![^<]*<\/span>)/gi, function(m) {
                return '<span class="sql-keyword">' + m + '</span>';
            });

            return result;
        }
    };
})();
