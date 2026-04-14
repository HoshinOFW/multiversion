package com.github.hoshinofw.multiversion.engine

import java.io.File

/**
 * In-memory representation of an origin map TSV file.
 *
 * Keys are either file-level (`com/Foo.java`) or member-level (`com/Foo.java#bar(int,String)`).
 * Values are origin paths with optional line numbers (`1.21.1/common/com/Foo.java:42`).
 */
class OriginMap() {

    private val entries = LinkedHashMap<String, String>()

    constructor(entries: Map<String, String>) : this() {
        this.entries.putAll(entries)
    }

    companion object {
        @JvmStatic
        fun fromFile(file: File): OriginMap {
            if (!file.exists()) return OriginMap()
            return OriginMap(parseTsv(file.readLines(Charsets.UTF_8)))
        }

        @JvmStatic
        fun fromString(tsv: String): OriginMap {
            if (tsv.isBlank()) return OriginMap()
            return OriginMap(parseTsv(tsv.lines()))
        }

        /**
         * Parses an origin map value like `"1.21.1/common/Foo.java:42:5"` into (path, line, col).
         * Backward-compatible with legacy `"path:line"` format (col defaults to 0).
         */
        @JvmStatic
        fun parseValue(raw: String): Triple<String, Int, Int> {
            val lastColon = raw.lastIndexOf(':')
            if (lastColon < 0) return Triple(raw, 0, 0)
            val lastNum = raw.substring(lastColon + 1).toIntOrNull()
            if (lastNum == null || lastNum <= 0) return Triple(raw, 0, 0)

            val beforeLast = raw.substring(0, lastColon)
            val secondColon = beforeLast.lastIndexOf(':')
            if (secondColon < 0) {
                // Legacy format: path:line (no col)
                return Triple(beforeLast, lastNum, 0)
            }
            val secondNum = beforeLast.substring(secondColon + 1).toIntOrNull()
            if (secondNum == null || secondNum <= 0) {
                // Second colon is part of path (e.g. C:), treat as path:line
                return Triple(beforeLast, lastNum, 0)
            }
            // path:line:col
            return Triple(beforeLast.substring(0, secondColon), secondNum, lastNum)
        }

        @JvmStatic
        fun parseTsv(lines: List<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>(maxOf(lines.size, 16))
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val tab = t.indexOf('\t')
                if (tab < 0) continue
                val k = t.substring(0, tab).replace('\\', '/').trim()
                val v = t.substring(tab + 1).replace('\\', '/').trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            return out
        }
    }

    // --- Query ---

    operator fun get(key: String): String? = entries[key]

    fun getMember(rel: String, memberKey: String): String? = entries["$rel#$memberKey"]

    fun getFile(rel: String): String? = entries[rel]

    val size: Int get() = entries.size

    // --- Mutation ---

    fun put(key: String, value: String) {
        entries[key] = value
    }

    /**
     * Adds pre-formatted TSV lines (each line is `key\tvalue`).
     */
    fun addEntries(tsvLines: List<String>) {
        for (line in tsvLines) {
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val k = line.substring(0, tab).trim()
            val v = line.substring(tab + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) entries[k] = v
        }
    }

    /**
     * Removes all entries for [rel] (both file-level and member-level),
     * then adds the [newEntries] (pre-formatted TSV lines).
     */
    fun patchFile(rel: String, newEntries: List<String>) {
        val memberPrefix = "$rel#"
        entries.keys.removeAll { it == rel || it.startsWith(memberPrefix) }
        addEntries(newEntries)
    }

    /**
     * Adds a file-level entry: `rel -> originPath`.
     */
    fun addFileEntry(rel: String, originPath: String) {
        entries[rel] = originPath
    }

    // --- Transform (for refactoring) ---

    /**
     * Updates all keys and values that reference [oldRel] to use [newRel].
     */
    fun renameFile(oldRel: String, newRel: String) {
        if (oldRel == newRel) return
        val snapshot = entries.entries.toList()
        entries.clear()
        for ((key, value) in snapshot) {
            val newKey = if (key == oldRel || key.startsWith("$oldRel#"))
                newRel + key.substring(oldRel.length)
            else key
            val newValue = value.replace(oldRel, newRel)
            entries[newKey] = newValue
        }
    }

    /**
     * Updates member key prefixes for a specific class file.
     * For example, renaming method "foo" to "bar" in `com/Foo.java`:
     * `renameMember("com/Foo.java", "foo(", "bar(")`.
     */
    fun renameMember(rel: String, oldPrefix: String, newPrefix: String) {
        if (oldPrefix == newPrefix) return
        val marker = "$rel#$oldPrefix"
        val snapshot = entries.entries.toList()
        entries.clear()
        for ((key, value) in snapshot) {
            val newKey = if (key.startsWith(marker))
                "$rel#$newPrefix${key.substring(marker.length)}"
            else key
            entries[newKey] = value
        }
    }

    // --- Serialization ---

    fun toLines(): List<String> = entries.map { (k, v) -> "$k\t$v" }

    override fun toString(): String {
        val lines = toLines()
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    fun toFile(file: File) {
        file.writeText(toString(), Charsets.UTF_8)
    }

    /**
     * Writes via a temp file in the same directory, then renames atomically.
     */
    @JvmOverloads
    fun toFileAtomic(file: File, tempSuffix: String = ".tmp") {
        val tmp = File(file.parentFile, file.name + tempSuffix)
        tmp.writeText(toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Fallback: renameTo can fail on some platforms if target exists
            file.delete()
            if (!tmp.renameTo(file)) {
                // Last resort: copy content
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
