package com.github.hoshinofw.multiversion.engine

import java.io.File
import java.util.EnumSet

/**
 * In-memory representation of an origin map TSV file.
 *
 * Keys are either file-level (`com/Foo.java`) or member-level (`com/Foo.java#bar(int,String)`).
 * Values are origin paths with optional line numbers (`1.21.1/common/com/Foo.java:42:5`).
 * An optional third tab-separated column carries space-separated [OriginFlag] tokens,
 * e.g. `TRUESRC SHADOW MODSIG` for a member declared with `@ShadowVersion @ModifySignature`.
 */
class OriginMap() {

    private val entries = LinkedHashMap<String, String>()
    private val entryFlags = HashMap<String, EnumSet<OriginFlag>>()

    companion object {
        @JvmStatic
        fun fromFile(file: File): OriginMap {
            if (!file.exists()) return OriginMap()
            val map = OriginMap()
            map.addEntries(file.readLines(Charsets.UTF_8))
            return map
        }

        @JvmStatic
        fun fromString(tsv: String): OriginMap {
            if (tsv.isBlank()) return OriginMap()
            val map = OriginMap()
            map.addEntries(tsv.lines())
            return map
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
                return Triple(beforeLast, lastNum, 0)
            }
            val secondNum = beforeLast.substring(secondColon + 1).toIntOrNull()
            if (secondNum == null || secondNum <= 0) {
                return Triple(beforeLast, lastNum, 0)
            }
            return Triple(beforeLast.substring(0, secondColon), secondNum, lastNum)
        }
    }

    // --- Query ---

    operator fun get(key: String): String? = entries[key]

    fun getMember(rel: String, memberKey: String): String? = entries["$rel#$memberKey"]

    fun getFile(rel: String): String? = entries[rel]

    val size: Int get() = entries.size

    fun getFlags(key: String): Set<OriginFlag> = entryFlags[key] ?: emptySet()

    fun getMemberFlags(rel: String, memberKey: String): Set<OriginFlag> =
        getFlags("$rel#$memberKey")

    fun isFileInTrueSrc(rel: String): Boolean =
        entryFlags[rel]?.contains(OriginFlag.TRUESRC) == true

    fun isMemberInTrueSrc(rel: String, memberKey: String): Boolean =
        entryFlags["$rel#$memberKey"]?.contains(OriginFlag.TRUESRC) == true

    /**
     * Returns the old member descriptor that [newMemberKey] replaced via @ModifySignature,
     * or null if no rename entry exists. Used for upstream version walking.
     */
    fun getRenameOldName(rel: String, newMemberKey: String): String? =
        entries["$rel#!rename#$newMemberKey"]

    /**
     * Returns the new member descriptor that replaced [oldMemberKey] via @ModifySignature,
     * or null if no rename entry exists. Used for downstream version walking.
     */
    fun getRenameNewName(rel: String, oldMemberKey: String): String? =
        entries["$rel#!renamed#$oldMemberKey"]

    /**
     * Returns all member-level origin entries for a given file, excluding rename tracking entries.
     */
    fun getMembersForFile(rel: String): Map<String, String> {
        val prefix = "$rel#"
        val result = LinkedHashMap<String, String>()
        for ((key, value) in entries) {
            if (!key.startsWith(prefix)) continue
            val memberKey = key.substring(prefix.length)
            if (memberKey.startsWith("!")) continue
            result[memberKey] = value
        }
        return result
    }

    // --- Mutation ---

    fun put(key: String, value: String) {
        entries[key] = value
        entryFlags.remove(key)
    }

    fun put(key: String, value: String, flags: Set<OriginFlag>) {
        entries[key] = value
        if (flags.isEmpty()) {
            entryFlags.remove(key)
        } else {
            entryFlags[key] = EnumSet.copyOf(flags)
        }
    }

    /**
     * Adds pre-formatted TSV lines. Each line is `key\tvalue` or `key\tvalue\tFLAGS` where
     * FLAGS is a space-separated list of [OriginFlag] tokens.
     */
    fun addEntries(tsvLines: List<String>) {
        for (line in tsvLines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val firstTab = t.indexOf('\t')
            if (firstTab < 0) continue
            val k = t.substring(0, firstTab).replace('\\', '/').trim()
            val rest = t.substring(firstTab + 1)
            val secondTab = rest.indexOf('\t')
            val v: String
            val flagStr: String
            if (secondTab < 0) {
                v = rest.replace('\\', '/').trim()
                flagStr = ""
            } else {
                v = rest.substring(0, secondTab).replace('\\', '/').trim()
                flagStr = rest.substring(secondTab + 1).trim()
            }
            if (k.isEmpty() || v.isEmpty()) continue
            entries[k] = v
            val flags = OriginFlag.parseFlags(flagStr)
            if (flags.isEmpty()) entryFlags.remove(k)
            else entryFlags[k] = EnumSet.copyOf(flags)
        }
    }

    /**
     * Removes all entries for [rel] (both file-level and member-level, including rename
     * tracking), then adds the [newEntries] (pre-formatted TSV lines).
     */
    fun patchFile(rel: String, newEntries: List<String>) {
        val memberPrefix = "$rel#"
        entries.keys.removeAll { it == rel || it.startsWith(memberPrefix) }
        entryFlags.keys.removeAll { it == rel || it.startsWith(memberPrefix) }
        addEntries(newEntries)
    }

    fun addFileEntry(rel: String, originPath: String) {
        entries[rel] = originPath
        entryFlags.remove(rel)
    }

    // --- Transform (for refactoring) ---

    fun renameFile(oldRel: String, newRel: String) {
        if (oldRel == newRel) return
        val snapshot = entries.entries.toList()
        val flagsSnapshot = entryFlags.entries.map { it.key to EnumSet.copyOf(it.value) }
        entries.clear()
        entryFlags.clear()
        for ((key, value) in snapshot) {
            val newKey = if (key == oldRel || key.startsWith("$oldRel#"))
                newRel + key.substring(oldRel.length)
            else key
            val newValue = value.replace(oldRel, newRel)
            entries[newKey] = newValue
        }
        for ((oldKey, flags) in flagsSnapshot) {
            val newKey = if (oldKey == oldRel || oldKey.startsWith("$oldRel#"))
                newRel + oldKey.substring(oldRel.length)
            else oldKey
            entryFlags[newKey] = flags
        }
    }

    fun renameMember(rel: String, oldPrefix: String, newPrefix: String) {
        if (oldPrefix == newPrefix) return
        val marker = "$rel#$oldPrefix"
        val snapshot = entries.entries.toList()
        val flagsSnapshot = entryFlags.entries.map { it.key to EnumSet.copyOf(it.value) }
        entries.clear()
        entryFlags.clear()
        for ((key, value) in snapshot) {
            val newKey = if (key.startsWith(marker))
                "$rel#$newPrefix${key.substring(marker.length)}"
            else key
            entries[newKey] = value
        }
        for ((oldKey, flags) in flagsSnapshot) {
            val newKey = if (oldKey.startsWith(marker))
                "$rel#$newPrefix${oldKey.substring(marker.length)}"
            else oldKey
            entryFlags[newKey] = flags
        }
    }

    // --- Serialization ---

    fun toLines(): List<String> = entries.map { (k, v) ->
        val flags = entryFlags[k]
        if (flags.isNullOrEmpty()) "$k\t$v"
        else "$k\t$v\t${OriginFlag.formatFlags(flags)}"
    }

    override fun toString(): String {
        val lines = toLines()
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    fun toFile(file: File) {
        file.writeText(toString(), Charsets.UTF_8)
    }

    @JvmOverloads
    fun toFileAtomic(file: File, tempSuffix: String = ".tmp") {
        val tmp = File(file.parentFile, file.name + tempSuffix)
        tmp.writeText(toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
