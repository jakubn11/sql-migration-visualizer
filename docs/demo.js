// Interactive dialect-aware generator preview.
// Outputs mirror MigrationGenerator + SchemaChangeRiskAnalyzer exactly.
(function () {
  "use strict";

  var CREATE_AUDIT =
    "CREATE TABLE audit_log (\n" +
    "    id INTEGER PRIMARY KEY,\n" +
    "    action TEXT NOT NULL,\n" +
    "    created_at TIMESTAMP\n" +
    ");";

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
    addtable: {
      label: "Add a table",
      sub: "new table audit_log",
      risk: {
        level: "MEDIUM",
        headline: "Moderate migration review recommended",
        items: ["audit_log adds required column(s): action"],
      },
      sql: {
        generic: CREATE_AUDIT,
        postgresql: CREATE_AUDIT,
        mysql: CREATE_AUDIT,
        sqlite: CREATE_AUDIT,
      },
    },
    droptable: {
      label: "Drop a table",
      sub: "legacy_sessions · removed",
      risk: {
        level: "HIGH",
        headline: "High-risk migration review recommended",
        items: [
          "legacy_sessions is removed entirely",
          "can delete data for every environment applying this migration",
        ],
      },
      sql: {
        generic: "DROP TABLE IF EXISTS legacy_sessions;",
        postgresql: "DROP TABLE IF EXISTS legacy_sessions;",
        mysql: "DROP TABLE IF EXISTS legacy_sessions;",
        sqlite: "DROP TABLE IF EXISTS legacy_sessions;",
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
    initInstallCopy();
    initSqlCopy();
    initScrollSpy();
  }

  function flash(btn, label) {
    var original = btn.dataset.label || btn.textContent;
    btn.dataset.label = original;
    btn.textContent = label;
    btn.classList.add("copied");
    setTimeout(function () {
      btn.textContent = original;
      btn.classList.remove("copied");
    }, 1800);
  }

  function copy(text, btn) {
    var done = function () { flash(btn, "Copied!"); };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text, done); });
    } else {
      fallbackCopy(text, done);
    }
  }

  function initInstallCopy() {
    var btn = document.getElementById("copyInstall");
    var cmd = document.getElementById("installCmd");
    if (!btn || !cmd) return;
    btn.addEventListener("click", function () {
      var text = cmd.innerText
        .split("\n")
        .map(function (l) { return l.replace(/^\$\s?/, ""); })
        .join("\n")
        .trim();
      copy(text, btn);
    });
  }

  function initSqlCopy() {
    var btn = document.getElementById("copySql");
    if (!btn) return;
    btn.addEventListener("click", function () {
      copy(SCENARIOS[state.scenario].sql[state.dialect], btn);
    });
  }

  function initScrollSpy() {
    var links = Array.prototype.slice.call(document.querySelectorAll(".nav-links a[href^='#']"));
    var map = {};
    var sections = [];
    links.forEach(function (a) {
      var id = a.getAttribute("href").slice(1);
      var sec = document.getElementById(id);
      if (sec) { map[id] = a; sections.push(sec); }
    });
    if (!sections.length || !("IntersectionObserver" in window)) return;

    var obs = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) {
          links.forEach(function (a) { a.classList.remove("active"); });
          var active = map[e.target.id];
          if (active) active.classList.add("active");
        }
      });
    }, { rootMargin: "-45% 0px -50% 0px", threshold: 0 });

    sections.forEach(function (s) { obs.observe(s); });
  }

  function fallbackCopy(text, done) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); done(); } catch (e) { /* no-op */ }
    document.body.removeChild(ta);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
