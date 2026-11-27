package io.github.jakub.sqlmigrationvisualizer.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigInteger

/**
 * A migration version: one or more whole numbers separated by dots, as Flyway
 * writes them — `V3__`, `V1.1__`, `V1_1__` (an underscore separates parts too)
 * or a timestamp such as `V202401151030__`.
 *
 * Parts are numbers of any size, so a 12- or 14-digit timestamp neither
 * overflows nor sorts as text, and versions compare part by part (`1.2` <
 * `1.10` < `2`). Trailing zero parts don't count (`1.0` is `1`), as in Flyway,
 * which treats those as the same version. The web UI receives the canonical
 * text (`"1.1"`, `"202401151030"`), never a JavaScript number, so nothing is
 * rounded on the way.
 */
@Serializable(with = MigrationVersionSerializer::class)
class MigrationVersion private constructor(
    /** Canonical parts: no leading zeros, no trailing zero parts beyond the first. */
    val parts: List<BigInteger>
) : Comparable<MigrationVersion> {

    val isSinglePart: Boolean get() = parts.size == 1

    /**
     * A single part of eight or more digits — a date or timestamp
     * (`20240115`, `202401151030`) rather than a counter. Such versions are
     * meant to leave gaps, so the validator doesn't report them as missing.
     */
    val isTimestampLike: Boolean get() = isSinglePart && parts[0] >= TIMESTAMP_MIN

    /**
     * The next whole version after this one: its first part plus one
     * (`12` → `13`, `1.2` → `2`, `202401151030` → `202401151031`). A whole
     * number fits every file name pattern, while a dotted one needs Flyway's
     * `V<version>__` form to be read back.
     */
    fun nextWhole(): MigrationVersion = MigrationVersion(listOf(parts[0] + BigInteger.ONE))

    override fun compareTo(other: MigrationVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val cmp = parts.getOrElse(i) { BigInteger.ZERO }.compareTo(other.parts.getOrElse(i) { BigInteger.ZERO })
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is MigrationVersion && parts == other.parts

    override fun hashCode(): Int = parts.hashCode()

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val TIMESTAMP_MIN: BigInteger = BigInteger.valueOf(10_000_000L)
        private val TEXT = Regex("""\d+(?:[._]\d+)*""")

        val ZERO: MigrationVersion = MigrationVersion(listOf(BigInteger.ZERO))

        fun of(value: Long): MigrationVersion = of(BigInteger.valueOf(value))

        fun of(value: BigInteger): MigrationVersion {
            require(value.signum() >= 0) { "A migration version can't be negative: $value" }
            return MigrationVersion(listOf(value))
        }

        /**
         * Reads `12`, `1.1`, `1_1` or `202401151030`; null for anything else
         * (empty text, a sign, letters, an empty part such as `1..2`).
         */
        fun parse(text: String): MigrationVersion? {
            val trimmed = text.trim()
            if (!TEXT.matches(trimmed)) return null
            return fromParts(trimmed.split('.', '_').map { BigInteger(it) })
        }

        /** The next whole version after every one given; `1` when there are none. */
        fun nextAfter(versions: Collection<MigrationVersion>): MigrationVersion =
            (versions.maxOrNull() ?: ZERO).nextWhole()

        private fun fromParts(raw: List<BigInteger>): MigrationVersion {
            var end = raw.size
            while (end > 1 && raw[end - 1].signum() == 0) end--
            return MigrationVersion(raw.subList(0, end).toList())
        }
    }
}

/** Serialises a [MigrationVersion] as its canonical text, so JSON never turns it into a rounded number. */
object MigrationVersionSerializer : KSerializer<MigrationVersion> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.jakub.sqlmigrationvisualizer.MigrationVersion", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MigrationVersion) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): MigrationVersion {
        val text = decoder.decodeString()
        return MigrationVersion.parse(text) ?: throw IllegalArgumentException("Not a migration version: $text")
    }
}
