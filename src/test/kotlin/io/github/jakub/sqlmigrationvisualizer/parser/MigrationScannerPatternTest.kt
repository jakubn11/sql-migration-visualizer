package io.github.jakub.sqlmigrationvisualizer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationScannerPatternTest {

    @Test
    fun `recognizes common migration filename patterns`() {
        assertEquals(1, MigrationScanner.parseMigrationVersion("1.sql"))
        assertEquals(2, MigrationScanner.parseMigrationVersion("2.sqm"))
        assertEquals(3, MigrationScanner.parseMigrationVersion("3_add_users.sql"))
        assertEquals(4, MigrationScanner.parseMigrationVersion("V4__add_posts.sql"))
        assertEquals(12, MigrationScanner.parseMigrationVersion("v12__seed_data.sqm"))
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
