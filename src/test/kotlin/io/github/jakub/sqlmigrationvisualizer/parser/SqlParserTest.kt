package io.github.jakub.sqlmigrationvisualizer.parser

import io.github.jakub.sqlmigrationvisualizer.model.MigrationFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlParserTest {

    private val parser = SqlParser()

    @Test
    fun `buildSchemaTimeline tracks rename and drop column changes`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE users (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sqm",
            fileName = "1.sqm",
            statements = listOf(
                "ALTER TABLE users RENAME COLUMN name TO full_name",
                "ALTER TABLE users DROP COLUMN full_name"
            ),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val versionOne = timeline.last()
        val usersTable = versionOne.tables["users"]

        assertNotNull(usersTable)
        assertEquals(listOf("id"), usersTable.columns.map { it.name })
        assertTrue(versionOne.changesSummary?.tablesModified?.contains("users") == true)
        assertEquals(listOf("name"), versionOne.changesSummary?.columnsRemoved?.get("users"))
    }

    @Test
    fun `buildSchemaTimeline supports postgres alter column statements`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE public.users (
              id BIGINT PRIMARY KEY,
              email TEXT
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sql",
            fileName = "1.sql",
            statements = listOf(
                "ALTER TABLE ONLY public.users ALTER COLUMN email TYPE VARCHAR(255)",
                "ALTER TABLE ONLY public.users ALTER COLUMN email SET NOT NULL",
                "ALTER TABLE ONLY public.users ALTER COLUMN email SET DEFAULT 'unknown@example.com'"
            ),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val usersTable = timeline.last().tables["public.users"]

        assertNotNull(usersTable)
        val emailColumn = usersTable.columns.firstOrNull { it.name == "email" }
        assertNotNull(emailColumn)
        assertEquals("VARCHAR(255)", emailColumn.type)
        assertEquals(false, emailColumn.nullable)
        assertEquals("'unknown@example.com'", emailColumn.defaultValue)
    }

    @Test
    fun `buildSchemaTimeline supports common mysql style change column and table rename`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE users (
              id BIGINT PRIMARY KEY
            )
            """.trimIndent(),
            """
            CREATE TABLE posts (
              id BIGINT PRIMARY KEY,
              user_id BIGINT REFERENCES users(id)
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sql",
            fileName = "1.sql",
            statements = listOf(
                "ALTER TABLE posts CHANGE COLUMN user_id author_id BIGINT NOT NULL REFERENCES users(id)",
                "ALTER TABLE users RENAME TO app_users"
            ),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val versionOne = timeline.last()
        val postsTable = versionOne.tables["posts"]
        val usersTable = versionOne.tables["app_users"]

        assertNotNull(postsTable)
        assertNotNull(usersTable)
        val authorId = postsTable.columns.firstOrNull { it.name == "author_id" }
        assertNotNull(authorId)
        assertEquals("BIGINT", authorId.type)
        assertEquals(false, authorId.nullable)
        assertEquals(1, postsTable.foreignKeys.size)
        assertEquals(listOf("author_id"), postsTable.foreignKeys.first().columns)
        assertEquals("app_users", postsTable.foreignKeys.first().referencedTable)
        assertEquals(listOf("id"), postsTable.foreignKeys.first().referencedColumns)
    }

    @Test
    fun `buildSchemaTimeline removes dependent foreign keys when referenced column is dropped`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE users (
              id BIGINT PRIMARY KEY,
              external_id TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE sessions (
              id BIGINT PRIMARY KEY,
              user_external_id TEXT REFERENCES users(external_id)
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sql",
            fileName = "1.sql",
            statements = listOf("ALTER TABLE users DROP COLUMN external_id"),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val sessionsTable = timeline.last().tables["sessions"]

        assertNotNull(sessionsTable)
        assertTrue(sessionsTable.foreignKeys.isEmpty())
    }

    @Test
    fun `buildSchemaTimeline supports dropping named foreign key constraints`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE orders (
              id BIGINT PRIMARY KEY,
              user_id BIGINT,
              CONSTRAINT fk_orders_users FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sql",
            fileName = "1.sql",
            statements = listOf("ALTER TABLE orders DROP CONSTRAINT fk_orders_users"),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val ordersTable = timeline.last().tables["orders"]

        assertNotNull(ordersTable)
        assertTrue(ordersTable.foreignKeys.isEmpty())
    }

    @Test
    fun `buildSchemaTimeline accumulates schema across multiple sequential migrations`() {
        val migration1 = MigrationFile(
            version = 1, filePath = "/tmp/1.sql", fileName = "1.sql", rawContent = "",
            statements = listOf("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        )
        val migration2 = MigrationFile(
            version = 2, filePath = "/tmp/2.sql", fileName = "2.sql", rawContent = "",
            statements = listOf(
                "CREATE TABLE posts (id INTEGER PRIMARY KEY, user_id INTEGER REFERENCES users(id))",
                "ALTER TABLE users ADD COLUMN email TEXT"
            )
        )

        val timeline = parser.buildSchemaTimeline(emptyList(), listOf(migration1, migration2))

        assertEquals(3, timeline.size)
        val finalState = timeline.last()
        assertNotNull(finalState.tables["users"])
        assertNotNull(finalState.tables["posts"])
        val emailCol = finalState.tables["users"]!!.columns.firstOrNull { it.name == "email" }
        assertNotNull(emailCol)
        assertEquals(1, finalState.tables["posts"]!!.foreignKeys.size)
        assertEquals("users", finalState.tables["posts"]!!.foreignKeys.first().referencedTable)
    }

    @Test
    fun `buildSchemaTimeline parses table-level constraint foreign key`() {
        val baseline = listOf(
            "CREATE TABLE users (id INTEGER PRIMARY KEY)",
            """
            CREATE TABLE orders (
              id INTEGER PRIMARY KEY,
              user_id INTEGER NOT NULL,
              CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """.trimIndent()
        )

        val timeline = parser.buildSchemaTimeline(baseline, emptyList())
        val ordersTable = timeline.first().tables["orders"]

        assertNotNull(ordersTable)
        assertEquals(1, ordersTable.foreignKeys.size)
        val fk = ordersTable.foreignKeys.first()
        assertEquals(listOf("user_id"), fk.columns)
        assertEquals("users", fk.referencedTable)
        assertEquals(listOf("id"), fk.referencedColumns)
        assertFalse(ordersTable.columns.any { it.name == "CONSTRAINT" })
    }

    @Test
    fun `buildSchemaTimeline supports mysql drop primary key`() {
        val baselineStatements = listOf(
            """
            CREATE TABLE users (
              id BIGINT PRIMARY KEY,
              email TEXT
            )
            """.trimIndent()
        )
        val migration = MigrationFile(
            version = 1,
            filePath = "/tmp/1.sql",
            fileName = "1.sql",
            statements = listOf("ALTER TABLE users DROP PRIMARY KEY"),
            rawContent = ""
        )

        val timeline = parser.buildSchemaTimeline(baselineStatements, listOf(migration))
        val usersTable = timeline.last().tables["users"]

        assertNotNull(usersTable)
        assertTrue(usersTable.primaryKey.isEmpty())
        assertTrue(usersTable.columns.none { it.isPrimaryKey })
    }
}
