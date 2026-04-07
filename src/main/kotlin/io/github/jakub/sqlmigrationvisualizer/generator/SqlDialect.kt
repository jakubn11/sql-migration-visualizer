package io.github.jakub.sqlmigrationvisualizer.generator

enum class SqlDialect(
    val id: String,
    val label: String
) {
    GENERIC("generic", "Generic SQL"),
    POSTGRESQL("postgresql", "PostgreSQL"),
    MYSQL("mysql", "MySQL / MariaDB");

    override fun toString(): String = label

    companion object {
        fun fromId(id: String?): SqlDialect =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GENERIC
    }
}
