package com.github.hoshinofw.multiversion.engine

import java.io.File
import java.util.EnumSet

/**
 * A compact origin position. Encodes where a member / file declaration lives without
 * embedding a path string. The resolver expands [v] to a version name and [s] to a
 * sibling rel via the per-version [ClassRoutingMap]; the caller wires both in.
 *
 * - [v]    Version index. `-1` marks a decl-column position (where V is implicit in the
 *          owning entry's version; the sentinel is only used in-memory and never written).
 * - [s]    Sibling index inside the target's alphabetical `@ModifyClass` routing list.
 *          `0` on plain (non-routed) classes resolves to the target rel itself.
 * - [line] 1-based line in the source file, or 0 when unknown.
 * - [col]  1-based column, or 0 when unknown.
 */
data class CompactPos(val v: Int, val s: Int, val line: Int, val col: Int) {
    companion object {
        const val V_DECL: Int = -1
    }
}

/**
 * In-memory representation of an origin-map TSV file.
 *
 * Wire format (v2). Header line at the top of every file:
 * ```
 * # multiversion-originmap v2
 * ```
 *
 * Entry forms:
 * ```
 * rel#desc\tV:S:L:C[|S:L:C][\tFLAGS]     (member entry; second position is the decl column)
 * rel\tV:S[\tFLAGS]                        (file entry)
 * rel#!rename#new\told                     (upstream rename lookup)
 * rel#!renamed#old\tnew                    (downstream rename lookup)
 * ```
 *
 * The decl column on a member entry is present only when that member carries
 * `@OverwriteVersion`, `@ModifySignature`, or is new in this version. It points at the
 * member's declaration in the current version's trueSrc (the file a `@ModifyClass`
 * sibling lives in, encoded by its alphabetical `S` index). The body column always
 * points at where the executing body lives, carrying its originating version's `V`.
 *
 * FLAGS are space-separated [OriginFlag] tokens (e.g. `TRUESRC OVERWRITE`) and never
 * collide with the colon-separated position form, so the third TSV column is
 * unambiguous between decl and flags — but by convention the parser treats a token with
 * colons as a position and a token with letters as flags. If both decl and flags are
 * present the decl is written to the inline `BODY|DECL` slot, never to the third column.
 */
class OriginMap() {

    private data class Entry(
        val body: String,
        val decl: String? = null,
        val flags: EnumSet<OriginFlag>? = null,
    )

    private val entries = LinkedHashMap<String, Entry>()

    companion object {
        const val FORMAT_VERSION: String = "2"
        const val FORMAT_HEADER: String = "# multiversion-originmap v$FORMAT_VERSION"

        @JvmStatic
        fun fromFile(file: File): OriginMap {
            if (!file.exists()) return OriginMap()
            val map = OriginMap()
            map.loadTsv(file.readLines(Charsets.UTF_8), source = file.path)
            return map
        }

        @JvmStatic
        fun fromString(tsv: String): OriginMap {
            if (tsv.isBlank()) return OriginMap()
            val map = OriginMap()
            map.loadTsv(tsv.lines(), source = "<string>")
            return map
        }

        /** Formats a body position `V:S:L:C`. */
        @JvmStatic
        fun fmtBody(v: Int, s: Int, line: Int, col: Int): String = "$v:$s:$line:$col"

        /** Formats a decl position `S:L:C` (version implicit). */
        @JvmStatic
        fun fmtDecl(s: Int, line: Int, col: Int): String = "$s:$line:$col"

        /** Formats a file origin `V:S`. */
        @JvmStatic
        fun fmtFile(v: Int, s: Int): String = "$v:$s"

        /**
         * Parses a body position string `V:S:L:C`. Returns null if the string is not a
         * well-formed 4-integer colon tuple.
         */
        @JvmStatic
        fun parseBody(raw: String): CompactPos? = parseCompact(raw, expectedArity = 4)?.let {
            CompactPos(it[0], it[1], it[2], it[3])
        }

        /**
         * Parses a decl position string `S:L:C`. Returns null if the string is not a
         * well-formed 3-integer colon tuple. [v] of the returned [CompactPos] is
         * [CompactPos.V_DECL].
         */
        @JvmStatic
        fun parseDecl(raw: String): CompactPos? = parseCompact(raw, expectedArity = 3)?.let {
            CompactPos(CompactPos.V_DECL, it[0], it[1], it[2])
        }

        /**
         * Parses a file origin string `V:S`. Returns null if the string is not a
         * well-formed 2-integer colon tuple.
         */
        @JvmStatic
        fun parseFile(raw: String): CompactPos? = parseCompact(raw, expectedArity = 2)?.let {
            CompactPos(it[0], it[1], 0, 0)
        }

        private fun parseCompact(raw: String, expectedArity: Int): IntArray? {
            val parts = raw.split(':')
            if (parts.size != expectedArity) return null
            val out = IntArray(expectedArity)
            for (i in parts.indices) {
                val n = parts[i].toIntOrNull() ?: return null
                out[i] = n
            }
            return out
        }

        /** True if [column3] is a flag token (letters) rather than a decl position (digits+colons). */
        private fun looksLikeFlags(column3: String): Boolean {
            if (column3.isBlank()) return false
            return column3.any { it.isLetter() }
        }
    }

    // --- Query ---------------------------------------------------------------

    operator fun get(key: String): String? = entries[key]?.body

    /**
     * Returns the raw body string for the member entry (`V:S:L:C`), or the old-descriptor
     * value for `!rename#` / `!renamed#` entries, or null if the key is absent. Used by
     * the engine's inheritance paths which copy body strings verbatim; IDE code must go
     * through [OriginResolver] instead.
     */
    fun getMember(rel: String, memberKey: String): String? = entries["$rel#$memberKey"]?.body

    /** Raw file origin string (`V:S`). Engine-internal; IDE uses [OriginResolver]. */
    fun getFile(rel: String): String? = entries[rel]?.body

    /** Typed body position for a member, or null if absent / not in body form. */
    fun getMemberBody(rel: String, memberKey: String): CompactPos? {
        val raw = entries["$rel#$memberKey"]?.body ?: return null
        return parseBody(raw)
    }

    /** Typed decl position for a member, or null if the entry has no decl column. */
    fun getMemberDecl(rel: String, memberKey: String): CompactPos? {
        val raw = entries["$rel#$memberKey"]?.decl ?: return null
        return parseDecl(raw)
    }

    /** Typed file origin position, or null if absent / not in file form. */
    fun getFileOrigin(rel: String): CompactPos? {
        val raw = entries[rel]?.body ?: return null
        return parseFile(raw)
    }

    val size: Int get() = entries.size

    fun getFlags(key: String): Set<OriginFlag> = entries[key]?.flags ?: emptySet()

    fun getMemberFlags(rel: String, memberKey: String): Set<OriginFlag> =
        getFlags("$rel#$memberKey")

    fun isFileInTrueSrc(rel: String): Boolean =
        entries[rel]?.flags?.contains(OriginFlag.TRUESRC) == true

    fun isMemberInTrueSrc(rel: String, memberKey: String): Boolean =
        entries["$rel#$memberKey"]?.flags?.contains(OriginFlag.TRUESRC) == true

    /**
     * Returns the old member descriptor that [newMemberKey] replaced via @ModifySignature,
     * or null if no rename entry exists. Used for upstream version walking.
     */
    fun getRenameOldName(rel: String, newMemberKey: String): String? =
        entries["$rel#!rename#$newMemberKey"]?.body

    /**
     * Returns the new member descriptor that replaced [oldMemberKey] via @ModifySignature,
     * or null if no rename entry exists. Used for downstream version walking.
     */
    fun getRenameNewName(rel: String, oldMemberKey: String): String? =
        entries["$rel#!renamed#$oldMemberKey"]?.body

    /**
     * Returns all member-level body strings for a given file, excluding rename tracking
     * entries. Values are raw body tokens (`V:S:L:C`); callers that need paths go through
     * [OriginResolver].
     */
    fun getMembersForFile(rel: String): Map<String, String> {
        val prefix = "$rel#"
        val result = LinkedHashMap<String, String>()
        for ((key, entry) in entries) {
            if (!key.startsWith(prefix)) continue
            val memberKey = key.substring(prefix.length)
            if (memberKey.startsWith("!")) continue
            result[memberKey] = entry.body
        }
        return result
    }

    // --- Mutation ------------------------------------------------------------

    fun put(key: String, body: String) {
        entries[key] = Entry(body)
    }

    fun put(key: String, body: String, flags: Set<OriginFlag>) {
        val flagSet = if (flags.isEmpty()) null else EnumSet.copyOf(flags)
        entries[key] = Entry(body, decl = null, flags = flagSet)
    }

    fun putMember(
        rel: String,
        memberKey: String,
        body: CompactPos,
        decl: CompactPos? = null,
        flags: Set<OriginFlag>? = null,
    ) {
        val flagSet = if (flags.isNullOrEmpty()) null else EnumSet.copyOf(flags)
        val declStr = decl?.let { fmtDecl(it.s, it.line, it.col) }
        entries["$rel#$memberKey"] = Entry(fmtBody(body.v, body.s, body.line, body.col), declStr, flagSet)
    }

    fun putFile(
        rel: String,
        origin: CompactPos,
        flags: Set<OriginFlag>? = null,
    ) {
        val flagSet = if (flags.isNullOrEmpty()) null else EnumSet.copyOf(flags)
        entries[rel] = Entry(fmtFile(origin.v, origin.s), decl = null, flags = flagSet)
    }

    /**
     * Writes both `!rename#` and `!renamed#` tracking entries for a @ModifySignature rename
     * (new descriptor <-> old descriptor). Rename entries carry no position or flags.
     */
    fun putRename(rel: String, newDesc: String, oldDesc: String) {
        entries["$rel#!rename#$newDesc"] = Entry(oldDesc)
        entries["$rel#!renamed#$oldDesc"] = Entry(newDesc)
    }

    /**
     * Convenience: stores a file-level entry from its already-formatted body string.
     * Kept for call sites that have a pre-built body token (e.g. inheritance copying).
     */
    fun addFileEntry(rel: String, body: String) {
        entries[rel] = Entry(body)
    }

    /**
     * Parses pre-formatted TSV lines. Accepts and skips the format-version header line
     * (and any other `#`-prefixed comments). Throws [MergeException] if a non-comment line
     * declares a different format version than [FORMAT_VERSION].
     *
     * Also accepts `addEntries` without a header (for in-flight emission from the engine,
     * where entries are generated and added to a map that was constructed in-memory).
     */
    fun addEntries(tsvLines: List<String>) {
        loadTsv(tsvLines, source = "<in-memory>", requireHeader = false)
    }

    private fun loadTsv(tsvLines: List<String>, source: String, requireHeader: Boolean = true) {
        var sawHeader = false
        for (line in tsvLines) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#")) {
                val maybeHeader = parseHeaderVersion(t)
                if (maybeHeader != null) {
                    if (maybeHeader != FORMAT_VERSION) {
                        throw MergeException(
                            "Origin map at $source is format v$maybeHeader but engine writes v$FORMAT_VERSION. " +
                                "Run generateAllPatchedSrc to regenerate."
                        )
                    }
                    sawHeader = true
                }
                continue
            }
            parseAndStore(t)
        }
        if (requireHeader && !sawHeader && entries.isNotEmpty()) {
            throw MergeException(
                "Origin map at $source is missing the '$FORMAT_HEADER' header line. " +
                    "This file was produced by an older engine; run generateAllPatchedSrc to regenerate."
            )
        }
    }

    private fun parseHeaderVersion(commentLine: String): String? {
        val stripped = commentLine.removePrefix("#").trim()
        val parts = stripped.split(Regex("\\s+"))
        if (parts.size < 2) return null
        if (parts[0] != "multiversion-originmap") return null
        val ver = parts[1]
        if (!ver.startsWith("v")) return null
        return ver.removePrefix("v")
    }

    private fun parseAndStore(trimmed: String) {
        val firstTab = trimmed.indexOf('\t')
        if (firstTab < 0) return
        val key = trimmed.substring(0, firstTab).replace('\\', '/').trim()
        if (key.isEmpty()) return

        val rest = trimmed.substring(firstTab + 1)
        val secondTab = rest.indexOf('\t')
        val valueField: String
        val tailField: String
        if (secondTab < 0) {
            valueField = rest.trim()
            tailField = ""
        } else {
            valueField = rest.substring(0, secondTab).trim()
            tailField = rest.substring(secondTab + 1).trim()
        }
        if (valueField.isEmpty()) return

        // Value field may carry an inline decl segment (BODY|DECL) on member entries.
        val pipe = valueField.indexOf('|')
        val body: String
        val decl: String?
        if (pipe >= 0) {
            body = valueField.substring(0, pipe).replace('\\', '/').trim()
            decl = valueField.substring(pipe + 1).replace('\\', '/').trim()
        } else {
            body = valueField.replace('\\', '/').trim()
            decl = null
        }

        val flags = if (tailField.isNotEmpty() && looksLikeFlags(tailField)) {
            OriginFlag.parseFlags(tailField)
        } else {
            emptySet()
        }
        val flagSet = if (flags.isEmpty()) null else EnumSet.copyOf(flags)
        entries[key] = Entry(body = body, decl = decl, flags = flagSet)
    }

    /**
     * Removes all entries for [rel] (both file-level and member-level, including rename
     * tracking), then adds the [newEntries] (pre-formatted TSV lines).
     */
    fun patchFile(rel: String, newEntries: List<String>) {
        val memberPrefix = "$rel#"
        entries.keys.removeAll { it == rel || it.startsWith(memberPrefix) }
        addEntries(newEntries)
    }

    // --- Transform (for refactoring) -----------------------------------------

    /**
     * Renames every key whose path prefix is [oldRel] to use [newRel] instead. Body and
     * decl position strings are compact (no embedded paths), so their values are preserved
     * verbatim.
     */
    fun renameFile(oldRel: String, newRel: String) {
        if (oldRel == newRel) return
        val snapshot = entries.entries.toList()
        entries.clear()
        for ((key, entry) in snapshot) {
            val newKey = if (key == oldRel || key.startsWith("$oldRel#"))
                newRel + key.substring(oldRel.length)
            else key
            entries[newKey] = entry
        }
    }

    /**
     * Renames every member key whose descriptor starts with [oldPrefix] inside [rel] to
     * use [newPrefix]. Values are preserved.
     */
    fun renameMember(rel: String, oldPrefix: String, newPrefix: String) {
        if (oldPrefix == newPrefix) return
        val marker = "$rel#$oldPrefix"
        val snapshot = entries.entries.toList()
        entries.clear()
        for ((key, entry) in snapshot) {
            val newKey = if (key.startsWith(marker))
                "$rel#$newPrefix${key.substring(marker.length)}"
            else key
            entries[newKey] = entry
        }
    }

    // --- Serialization -------------------------------------------------------

    fun toLines(): List<String> {
        val out = ArrayList<String>(entries.size + 1)
        out.add(FORMAT_HEADER)
        for ((k, entry) in entries) {
            val bodyField = if (entry.decl != null) "${entry.body}|${entry.decl}" else entry.body
            val line = if (entry.flags.isNullOrEmpty())
                "$k\t$bodyField"
            else
                "$k\t$bodyField\t${OriginFlag.formatFlags(entry.flags)}"
            out.add(line)
        }
        return out
    }

    override fun toString(): String {
        val lines = toLines()
        return lines.joinToString("\n") + "\n"
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
