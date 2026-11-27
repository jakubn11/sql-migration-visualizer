package io.github.jakub.sqlmigrationvisualizer.parser

import io.github.jakub.sqlmigrationvisualizer.model.MigrationVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationScannerPatternTest {

    @Test
    fun `recognizes common migration filename patterns`() {
        assertEquals(MigrationVersion.of(1), MigrationScanner.parseMigrationVersion("1.sql"))
        assertEquals(MigrationVersion.of(2), MigrationScanner.parseMigrationVersion("2.sqm"))
        assertEquals(MigrationVersion.of(3), MigrationScanner.parseMigrationVersion("3_add_users.sql"))
        assertEquals(MigrationVersion.of(4), MigrationScanner.parseMigrationVersion("V4__add_posts.sql"))
        assertEquals(MigrationVersion.of(12), MigrationScanner.parseMigrationVersion("v12__seed_data.sqm"))
    }

    @Test
    fun `reads Flyway dotted underscored and timestamp versions`() {
        assertEquals(MigrationVersion.parse("1.1"), MigrationScanner.parseMigrationVersion("V1.1__add_index.sql"))
        assertEquals(MigrationVersion.parse("1.1"), MigrationScanner.parseMigrationVersion("V1_1__add_index.sql"))
        assertEquals(MigrationVersion.parse("2.0.1"), MigrationScanner.parseMigrationVersion("V2_0_1__fix.sqm"))
        assertEquals(MigrationVersion.parse("202401151030"), MigrationScanner.parseMigrationVersion("V202401151030__create_users.sql"))
        assertEquals(MigrationVersion.parse("20240115103045"), MigrationScanner.parseMigrationVersion("20240115103045_create_users.sql"))
        assertEquals(MigrationVersion.of(2), MigrationScanner.parseMigrationVersion("V2.0__init.sql"))
        assertEquals(null, MigrationScanner.parseMigrationVersion("V1..2__broken.sql"))
    }

    @Test
    fun `versioned Flyway files are migrations, not schema files`() {
        assertTrue(MigrationScanner.isMigrationFileName("V1.1__add_index.sql"))
        assertFalse(MigrationScanner.isSchemaFileName("V1.1__add_index.sql"))
        assertFalse(MigrationScanner.isSchemaFileName("V202401151030__create_users.sql"))
    }

    @Test
    fun `Flyway repeatable undo and baseline files are neither migrations nor schema files`() {
        for (name in listOf("R__refresh_views.sql", "U3__undo_orders.sql", "u1.1__undo.sql", "B5__baseline.sql")) {
            assertFalse(MigrationScanner.isMigrationFileName(name), name)
            assertFalse(MigrationScanner.isSchemaFileName(name), name)
        }
        assertTrue(MigrationScanner.isSchemaFileName("Reports.sql"))
    }

    @Test
    fun `does not treat plain schema files as migrations`() {
        assertFalse(MigrationScanner.isMigrationFileName("schema.sql"))
        assertFalse(MigrationScanner.isMigrationFileName("Tables.sq"))
        assertFalse(MigrationScanner.isMigrationFileName("users.ddl"))
        assertTrue(MigrationScanner.isSchemaFileName("schema.sql"))
        assertTrue(MigrationScanner.isSchemaFileName("Tables.sq"))
        assertTrue(MigrationScanner.isSchemaFileName("users.ddl"))
    }

    @Test
    fun `prefers existing migration extension when available`() {
        assertEquals(
            "sqm",
            MigrationScanner.detectPreferredMigrationExtension(listOf("/tmp/1.sqm", "/tmp/2.sqm"))
        )
        assertEquals(
            "sql",
            MigrationScanner.detectPreferredMigrationExtension(listOf("/tmp/V1__init.sql"))
        )
        assertEquals(
            "sql",
            MigrationScanner.detectPreferredMigrationExtension(emptyList())
        )
    }

    @Test
    fun `splits statements without breaking on semicolons inside strings comments and dollar quotes`() {
        val sql = """
            -- leading comment;
            CREATE TABLE users (
              id INTEGER PRIMARY KEY,
              note TEXT DEFAULT 'semi;colon'
            );
            /* block; comment */
            CREATE FUNCTION demo() RETURNS trigger AS $$
            BEGIN
              RAISE NOTICE 'hello;world';
              RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            INSERT INTO users(note) VALUES ('still;one;statement');
        """.trimIndent()

        val statements = MigrationScanner.parseStatements(sql)

        assertEquals(3, statements.size)
        assertTrue(statements[0].startsWith("CREATE TABLE users"))
        assertTrue(statements[1].startsWith("CREATE FUNCTION demo()"))
        assertTrue(statements[2].startsWith("INSERT INTO users"))
    }
}
