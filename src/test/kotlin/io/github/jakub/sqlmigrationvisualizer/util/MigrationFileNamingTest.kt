package io.github.jakub.sqlmigrationvisualizer.util

import io.github.jakub.sqlmigrationvisualizer.model.MigrationVersion
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationFileNamingTest {

    @Test
    fun `builds migration file names from configurable pattern`() {
        val fileName = MigrationFileNaming.buildFileName(
            pattern = "V{version}__{name}",
            version = MigrationVersion.of(12),
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
            version = MigrationVersion.of(3),
            name = "Seed Roles",
            extension = "sqm",
            now = LocalDateTime.of(2026, 4, 6, 10, 30, 45)
        )

        assertEquals("20260406103045_seed_roles.sqm", fileName)
    }

    @Test
    fun `renumber preserves underscore-suffixed naming`() {
        assertEquals("7_add_users.sql", MigrationFileNaming.renumberFileName("3_add_users.sql", MigrationVersion.of(7)))
    }

    @Test
    fun `renumber preserves Flyway prefix style`() {
        assertEquals("V12__create_orders.sql", MigrationFileNaming.renumberFileName("V3__create_orders.sql", MigrationVersion.of(12)))
        assertEquals("v12__create_orders.sqm", MigrationFileNaming.renumberFileName("v3__create_orders.sqm", MigrationVersion.of(12)))
    }

    @Test
    fun `renumber replaces a dotted or underscored Flyway version`() {
        assertEquals("V2__add_index.sql", MigrationFileNaming.renumberFileName("V1.1__add_index.sql", MigrationVersion.of(2)))
        assertEquals("V1.2__add_index.sql", MigrationFileNaming.renumberFileName("V1_1__add_index.sql", MigrationVersion.parse("1.2")!!))
        assertEquals(
            "V202401151031__create_users.sql",
            MigrationFileNaming.renumberFileName("V202401151030__create_users.sql", MigrationVersion.parse("202401151031")!!)
        )
    }

    @Test
    fun `builds a Flyway name with a dotted version`() {
        val fileName = MigrationFileNaming.buildFileName(
            pattern = "V{version}__{name}",
            version = MigrationVersion.parse("1.2")!!,
            name = "Add Index",
            extension = "sql"
        )

        assertEquals("V1.2__add_index.sql", fileName)
    }

    @Test
    fun `renumber handles plain numeric file names`() {
        assertEquals("9.sql", MigrationFileNaming.renumberFileName("3.sql", MigrationVersion.of(9)))
    }

    @Test
    fun `renumber returns null for unrecognised name`() {
        assertEquals(null, MigrationFileNaming.renumberFileName("not-a-migration.txt", MigrationVersion.of(5)))
    }

    @Test
    fun `nextFreeVersion returns one past the maximum used`() {
        assertEquals(MigrationVersion.of(4), MigrationFileNaming.nextFreeVersion(versions(1, 2, 3)))
        assertEquals(MigrationVersion.of(11), MigrationFileNaming.nextFreeVersion(versions(10, 3, 7)))
        assertEquals(MigrationVersion.of(1), MigrationFileNaming.nextFreeVersion(emptyList()))
    }

    @Test
    fun `nextFreeVersion is a whole number after dotted and timestamp versions`() {
        assertEquals(MigrationVersion.of(2), MigrationFileNaming.nextFreeVersion(listOf("1", "1.2", "1.10").map { MigrationVersion.parse(it)!! }))
        assertEquals(
            MigrationVersion.parse("202401151031"),
            MigrationFileNaming.nextFreeVersion(listOf("3", "202401151030").map { MigrationVersion.parse(it)!! })
        )
    }

    private fun versions(vararg values: Long) = values.map { MigrationVersion.of(it) }
}
