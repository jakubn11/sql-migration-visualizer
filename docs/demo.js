// Interactive dialect-aware generator preview.
// Outputs mirror MigrationGenerator + SchemaChangeRiskAnalyzer exactly.
(function () {
  "use strict";

  var SCENARIOS = {
    retype: {
      label: "Retype a column",
      sub: "users.email · TEXT → VARCHAR(255) NOT NULL",
      risk: {
        level: "HIGH",
        headline: "High-risk migration review recommended",
        items: [
          "type narrows from TEXT to VARCHAR(255)",
          "nullability tightens to NOT NULL",
        ],
      },
      sql: {
        generic:
          "-- Rebuild users to apply complex schema changes safely.\n" +
          "CREATE TABLE users__new (\n" +
          "    id INTEGER PRIMARY KEY,\n" +
          "    email VARCHAR(255) NOT NULL\n" +
          ");\n\n" +
          "INSERT INTO users__new (id, email)\n" +
          "SELECT id, email\n" +
          "FROM users;\n\n" +
          "DROP TABLE users;\n\n" +
          "ALTER TABLE users__new RENAME TO users;",
        postgresql:
          "ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(255);\n\n" +
          "ALTER TABLE users ALTER COLUMN email SET NOT NULL;",
        mysql:
          "ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL;",
        sqlite:
          "-- Rebuild users to apply complex schema changes safely.\n" +
          "-- SQLite cannot ALTER COLUMN type/nullability/default in place; a 12-step rebuild is the standard workaround.\n" +
          "-- Review any indexes, triggers, or views referencing users — they must be recreated after the rename.\n" +
          "PRAGMA foreign_keys=OFF;\n\n" +
          "BEGIN TRANSACTION;\n\n" +
          "CREATE TABLE users__new (\n" +
          "    id INTEGER PRIMARY KEY,\n" +
          "    email VARCHAR(255) NOT NULL\n" +
          ");\n\n" +
          "INSERT INTO users__new (id, email)\n" +
          "SELECT id, email\n" +
          "FROM users;\n\n" +
          "DROP TABLE users;\n\n" +
          "ALTER TABLE users__new RENAME TO users;\n\n" +
          "COMMIT;\n\n" +
          "PRAGMA foreign_keys=ON;",
      },
    },
    addcol: {
      label: "Add a column",
      sub: "users.last_login · new TIMESTAMP NULL",
      risk: {
        level: "LOW",
        headline: "Low-risk additive change",
        items: ["new nullable column, no backfill required"],
      },
      sql: {
        generic:
          "ALTER TABLE users ADD COLUMN last_login TIMESTAMP;",
        postgresql:
          "ALTER TABLE users ADD COLUMN last_login TIMESTAMP;",
        mysql:
          "ALTER TABLE users ADD COLUMN last_login TIMESTAMP;",
        sqlite:
          "ALTER TABLE users ADD COLUMN last_login TIMESTAMP;",
      },
    },
    dropcol: {
      label: "Drop a column",
      sub: "users.legacy_token · removed",
      risk: {
        level: "HIGH",
        headline: "High-risk migration review recommended",
        items: [
          "users.legacy_token is removed, which can discard data",
          "older queries referencing the column will break",
        ],
      },
      sql: {
        generic:
          "-- Rebuild users to apply complex schema changes safely.\n" +
          "CREATE TABLE users__new (\n" +
          "    id INTEGER PRIMARY KEY,\n" +
          "    email VARCHAR(255) NOT NULL\n" +
          ");\n\n" +
          "INSERT INTO users__new (id, email)\n" +
          "SELECT id, email\n" +
          "FROM users;\n\n" +
          "DROP TABLE users;\n\n" +
          "ALTER TABLE users__new RENAME TO users;",
        postgresql:
          "ALTER TABLE users DROP COLUMN legacy_token;",
        mysql:
          "ALTER TABLE users DROP COLUMN legacy_token;",
        sqlite:
          "-- Rebuild users to apply complex schema changes safely.\n" +
          "-- SQLite cannot ALTER COLUMN type/nullability/default in place; a 12-step rebuild is the standard workaround.\n" +
          "-- Review any indexes, triggers, or views referencing users — they must be recreated after the rename.\n" +
          "PRAGMA foreign_keys=OFF;\n\n" +
          "BEGIN TRANSACTION;\n\n" +
          "CREATE TABLE users__new (\n" +
          "    id INTEGER PRIMARY KEY,\n" +
          "    email VARCHAR(255) NOT NULL\n" +
          ");\n\n" +
          "INSERT INTO users__new (id, email)\n" +
          "SELECT id, email\n" +
          "FROM users;\n\n" +
          "DROP TABLE users;\n\n" +
          "ALTER TABLE users__new RENAME TO users;\n\n" +
          "COMMIT;\n\n" +
          "PRAGMA foreign_keys=ON;",
      },
    },
  };

  var DIALECTS = [
    { id: "generic", label: "Generic" },
    { id: "postgresql", label: "PostgreSQL" },
    { id: "mysql", label: "MySQL" },
    { id: "sqlite", label: "SQLite" },
  ];

  var KEYWORDS = new Set([
    "CREATE", "TABLE", "INSERT", "INTO", "SELECT", "FROM", "DROP", "ALTER",
    "COLUMN", "RENAME", "TO", "SET", "NOT", "NULL", "PRIMARY", "KEY", "ADD",
    "BEGIN", "TRANSACTION", "COMMIT", "PRAGMA", "MODIFY", "TYPE", "DEFAULT",
    "IF", "EXISTS", "ON", "OFF", "AS",
  ]);
  var TYPES = new Set([
    "INTEGER", "VARCHAR", "TEXT", "BIGINT", "BOOLEAN", "TIMESTAMP", "REAL", "BLOB", "DATE",
  ]);

  var state = { scenario: "retype", dialect: "postgresql" };

  function highlightLine(line) {
    var c = line.indexOf("--");
    if (c !== -1) {
      return tokens(line.slice(0, c)) +
        '<span class="tok-comment">' + line.slice(c) + "</span>";
    }
    return tokens(line);
  }

  function tokens(s) {
    return s.replace(/('[^']*')|(\b\d+\b)|([A-Za-z_][A-Za-z0-9_]*)/g,
      function (m, str, num, word) {
        if (str) return '<span class="tok-string">' + str + "</span>";
        if (num) return '<span class="tok-number">' + num + "</span>";
        var u = word.toUpperCase();
        if (KEYWORDS.has(u)) return '<span class="tok-keyword">' + word + "</span>";
        if (TYPES.has(u)) return '<span class="tok-type">' + word + "</span>";
        return word;
      });
  }

  function highlight(sql) {
    return sql.split("\n").map(highlightLine).join("\n");
  }

  function makeChip(group, value, label, onClick) {
    var b = document.createElement("button");
    b.className = "seg-chip";
    b.type = "button";
    b.setAttribute("role", "tab");
    b.dataset.group = group;
    b.dataset.value = value;
    b.textContent = label;
    b.addEventListener("click", onClick);
    return b;
  }

  function render() {
    var sc = SCENARIOS[state.scenario];

    document.querySelectorAll("#scenarioSeg .seg-chip").forEach(function (c) {
      c.classList.toggle("is-active", c.dataset.value === state.scenario);
    });
    document.querySelectorAll("#dialectSeg .seg-chip").forEach(function (c) {
      c.classList.toggle("is-active", c.dataset.value === state.dialect);
    });

    var pane = document.getElementById("sqlOutput");
    pane.classList.remove("flash");
    void pane.offsetWidth; // restart animation
    pane.innerHTML = highlight(sc.sql[state.dialect]);
    pane.classList.add("flash");

    document.getElementById("outputFile").textContent =
      "V2__" + sc.label.toLowerCase().replace(/[^a-z]+/g, "_").replace(/^_|_$/g, "") + ".sql";

    var badge = document.getElementById("riskBadge");
    badge.textContent = sc.risk.level;
    badge.className = "risk-badge risk-" + sc.risk.level.toLowerCase();
    document.getElementById("riskHeadline").textContent = sc.risk.headline;

    var items = document.getElementById("riskItems");
    items.innerHTML = "";
    sc.risk.items.forEach(function (t) {
      var li = document.createElement("li");
      li.textContent = t;
      items.appendChild(li);
    });
  }

  function init() {
    var scSeg = document.getElementById("scenarioSeg");
    Object.keys(SCENARIOS).forEach(function (key) {
      scSeg.appendChild(makeChip("scenario", key, SCENARIOS[key].label, function () {
        state.scenario = key;
        render();
      }));
    });

    var dSeg = document.getElementById("dialectSeg");
    DIALECTS.forEach(function (d) {
      dSeg.appendChild(makeChip("dialect", d.id, d.label, function () {
        state.dialect = d.id;
        render();
      }));
    });

    render();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
