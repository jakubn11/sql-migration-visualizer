package io.github.jakub.sqlmigrationvisualizer.util

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationFileNamingTest {

    @Test
    fun `builds migration file names from configurable pattern`() {
        val fileName = MigrationFileNaming.buildFileName(
            pattern = "V{version}__{name}",
            version = 12,
            name = "Add User Audit Log",
            extension = "sql",
            now = LocalDateTime.of(2026, 4, 6, 10, 30, 45)
        )

        assertEquals("V12__add_user_audit_log.sql", fileName)
    }

    @Test
    fun `supports timestamp token and extension placeholder`() {
        val fileName = MigrationFileNaming.buildFileName(
            pattern = "{timestamp}_{name}.{extension}",
            version = 3,
            name = "Seed Roles",
            extension = "sqm",
            now = LocalDateTime.of(2026, 4, 6, 10, 30, 45)
        )

        assertEquals("20260406103045_seed_roles.sqm", fileName)
    }

    @Test
    fun `renumber preserves underscore-suffixed naming`() {
        assertEquals("7_add_users.sql", MigrationFileNaming.renumberFileName("3_add_users.sql", 7))
    }

    @Test
    fun `renumber preserves Flyway prefix style`() {
        assertEquals("V12__create_orders.sql", MigrationFileNaming.renumberFileName("V3__create_orders.sql", 12))
        assertEquals("v12__create_orders.sqm", MigrationFileNaming.renumberFileName("v3__create_orders.sqm", 12))
    }

    @Test
    fun `renumber handles plain numeric file names`() {
        assertEquals("9.sql", MigrationFileNaming.renumberFileName("3.sql", 9))
    }

    @Test
    fun `renumber returns null for unrecognised name`() {
        assertEquals(null, MigrationFileNaming.renumberFileName("not-a-migration.txt", 5))
    }

    @Test
    fun `nextFreeVersion returns one past the maximum used`() {
        assertEquals(4, MigrationFileNaming.nextFreeVersion(listOf(1, 2, 3)))
        assertEquals(11, MigrationFileNaming.nextFreeVersion(listOf(10, 3, 7)))
        assertEquals(1, MigrationFileNaming.nextFreeVersion(emptyList()))
    }
}
