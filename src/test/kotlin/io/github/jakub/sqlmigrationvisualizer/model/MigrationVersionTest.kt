package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationVersionTest {

    @Test
    fun `parses whole dotted underscored and timestamp versions`() {
        assertEquals("12", v("12").toString())
        assertEquals("1.1", v("1.1").toString())
        assertEquals("1.1", v("1_1").toString())
        assertEquals("1.2", v("01.02").toString())
        assertEquals("202401151030", v("202401151030").toString())
        assertEquals("99999999999999999999", v("99999999999999999999").toString())
    }

    @Test
    fun `rejects text that is not a version`() {
        for (text in listOf("", " ", "1..2", "1.", ".1", "-1", "+1", "1a", "V1", "1,2")) {
            assertNull(MigrationVersion.parse(text), "'$text'")
        }
        assertFailsWith<IllegalArgumentException> { MigrationVersion.of(-1) }
    }

    @Test
    fun `trailing zero parts do not count`() {
        assertEquals(v("1"), v("1.0"))
        assertEquals(v("1"), v("1_0_0"))
        assertEquals(v("1").hashCode(), v("1.0").hashCode())
        assertEquals("1", v("1.0").toString())
        assertEquals("0", v("0.0").toString())
        assertEquals(v("1.0.1"), v("1.0.1"))
    }

    @Test
    fun `versions compare part by part`() {
        val sorted = listOf("2", "1.10", "202401151030", "1.2", "1", "1.2.1").map(::v).sorted().map { it.toString() }
        assertEquals(listOf("1", "1.2", "1.2.1", "1.10", "2", "202401151030"), sorted)
        assertTrue(v("202401151030") < v("202401151031"))
        assertEquals(0, v("3").compareTo(v("3.0")))
    }

    @Test
    fun `timestamp-like means a single part of eight or more digits`() {
        assertFalse(v("9999999").isTimestampLike)
        assertTrue(v("10000000").isTimestampLike)
        assertTrue(v("202401151030").isTimestampLike)
        assertFalse(v("20240115.1").isTimestampLike)
        assertTrue(v("3").isSinglePart)
        assertFalse(v("3.1").isSinglePart)
    }

    @Test
    fun `next version is one past the highest first part`() {
        assertEquals(v("1"), MigrationVersion.nextAfter(emptyList()))
        assertEquals(v("13"), MigrationVersion.nextAfter(listOf(v("12"), v("3"))))
        assertEquals(v("2"), MigrationVersion.nextAfter(listOf(v("1.9"), v("1.10"))))
        assertEquals(v("202401151031"), MigrationVersion.nextAfter(listOf(v("5"), v("202401151030"))))
    }

    @Test
    fun `serialises as text so JavaScript never rounds it`() {
        val file = MigrationFile(
            version = v("20240115103045123"),
            filePath = "/tmp/V20240115103045123__x.sql",
            fileName = "V20240115103045123__x.sql",
            statements = emptyList(),
            rawContent = ""
        )
        val json = Json.encodeToString(file)
        assertTrue(json.contains("\"version\":\"20240115103045123\""), json)
        assertEquals(file.version, Json.decodeFromString<MigrationFile>(json).version)
        assertEquals(v("1.1"), Json.decodeFromString<MigrationVersion>("\"1_1\""))
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<MigrationVersion>("\"latest\"") }
    }

    private fun v(text: String): MigrationVersion = MigrationVersion.parse(text)!!
}
