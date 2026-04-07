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
}
