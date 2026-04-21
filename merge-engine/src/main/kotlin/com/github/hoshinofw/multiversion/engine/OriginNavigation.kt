package com.github.hoshinofw.multiversion.engine

/**
 * Pure origin-map-based navigation walkers, shared between the Gradle plugin and the IDE.
 *
 * The IDE is responsible for:
 * 1. Resolving the ordered list of version directories (project-structure knowledge).
 * 2. Loading each version's [OriginMap] (via its cache) into a `List<OriginMap?>` aligned
 *    with the version ordering. A `null` entry denotes a version whose map has not been
 *    generated yet.
 * 3. Calling into this object with (maps, currentIdx, rel, caretKey, direction).
 *
 * The walkers follow `!rename#` / `!renamed#` entries so `@ModifySignature` renames
 * chain across multiple versions without opening any PSI. They return indices into the
 * same list the IDE provided, so the IDE can map each hit back to a concrete file.
 *
 * All functions here are pure functions of their arguments, so walker output can be
 * memoised by any caller. [OriginMap] instances returned by the engine's [CachedOriginMap]
 * are already invalidated when the underlying file changes, making identity-hash
 * memoisation safe without additional invalidation plumbing.
 *
 * ## Flag filter semantics
 *
 * All walkers take a `filter: Set<OriginFlag>`:
 *
 * - **Empty set** matches any entry that exists at the tracked key, including
 *   pure-inherited entries that carry no flags. Use this for "does the member exist in
 *   any upstream version" existence checks.
 * - **Non-empty set** matches only entries whose flag set intersects [filter]. Since
 *   [OriginFlag.OVERWRITE] / [OriginFlag.SHADOW] / [OriginFlag.MODSIG] / [OriginFlag.NEW]
 *   are only set alongside [OriginFlag.TRUESRC], any non-empty filter with those flags
 *   implicitly restricts the walk to trueSrc-declaring versions.
 *
 * Pre-built filter sets:
 * - [DECLARATION_FLAGS] — `{OVERWRITE, MODSIG, NEW}`. "The member has a real body /
 *   declaration here." Used by gutter arrows and keybinds.
 * - [SIGNATURE_FLAGS] — `{MODSIG, NEW}`. "The member's signature was defined at this
 *   version." Used to find the canonical declaration for annotation-source inspections.
 */
object OriginNavigation {

    /**
     * `{OVERWRITE, MODSIG, NEW}` — versions that declare the member's body / signature in
     * their own right (not just a `@ShadowVersion` reference, not pure inheritance).
     */
    val DECLARATION_FLAGS: Set<OriginFlag> = java.util.EnumSet.of(
        OriginFlag.OVERWRITE, OriginFlag.MODSIG, OriginFlag.NEW,
    )

    /**
     * `{MODSIG, NEW}` — versions that (re)define the member's signature. Every version
     * between a `SIGNATURE_FLAGS` hit and the next one downstream shares that signature's
     * lifetime: its annotations, parameter types, return type, and rename identity all
     * follow from the hit. Used by the inspection that flags missing original annotations.
     */
    val SIGNATURE_FLAGS: Set<OriginFlag> = java.util.EnumSet.of(
        OriginFlag.MODSIG, OriginFlag.NEW,
    )

    /**
     * `{OVERWRITE, SHADOW, MODSIG, NEW}` — every flag that accompanies a trueSrc member
     * declaration. Matches any version whose trueSrc declares the member in its own right
     * (body, shadow reference, signature change, or brand-new). Excludes pure-inheritance
     * entries (no flags) and file-only entries.
     */
    val ANY_DECLARATION_FLAGS: Set<OriginFlag> = java.util.EnumSet.of(
        OriginFlag.OVERWRITE, OriginFlag.SHADOW, OriginFlag.MODSIG, OriginFlag.NEW,
    )

    /** A single trueSrc declaration of a tracked member in some version. */
    data class TrueSrcMemberHit(
        val versionIdx: Int,
        val memberKey: String,          // post-rename key at [versionIdx]
        val flags: Set<OriginFlag>,     // from maps[versionIdx]
    )

    /** A single version that owns a trueSrc file for this class. */
    data class TrueSrcClassHit(
        val versionIdx: Int,
        val flags: Set<OriginFlag>,
    )

    /** Per-version view for the Alt+Shift+V popup. */
    sealed interface MemberVersionView {
        val versionIdx: Int
        data class TrueSrc(
            override val versionIdx: Int,
            val memberKey: String,
            val flags: Set<OriginFlag>,
        ) : MemberVersionView
        data class Inherited(
            override val versionIdx: Int,
            val memberKey: String,
        ) : MemberVersionView
        data class Absent(override val versionIdx: Int) : MemberVersionView
    }

    sealed interface ClassVersionView {
        val versionIdx: Int
        data class TrueSrc(
            override val versionIdx: Int,
            val flags: Set<OriginFlag>,
        ) : ClassVersionView
        data class Inherited(
            override val versionIdx: Int,
        ) : ClassVersionView
        data class Absent(override val versionIdx: Int) : ClassVersionView
    }

    /**
     * Entry-level membership test: does this map have any entry (trueSrc or inherited) at
     * `rel#memberKey`, AND if [filter] is non-empty, does at least one of its flags match?
     * Empty filter falls back to plain existence.
     */
    private fun OriginMap.matchesMember(rel: String, memberKey: String, filter: Set<OriginFlag>): Boolean {
        if (filter.isEmpty()) return getMember(rel, memberKey) != null
        val flags = getMemberFlags(rel, memberKey)
        return flags.any { it in filter }
    }

    /** Same shape as [matchesMember] for file entries. */
    private fun OriginMap.matchesFile(rel: String, filter: Set<OriginFlag>): Boolean {
        if (filter.isEmpty()) return getFile(rel) != null
        val flags = getFlags(rel)
        return flags.any { it in filter }
    }

    // --- Member walks --------------------------------------------------------

    /**
     * Returns the starting key to use for an upstream walk. If the caret's current
     * version renamed the caret member (via @ModifySignature), the upstream walk
     * continues under the old key. Otherwise returns [caretKey] unchanged.
     */
    fun normalizeCaretKeyForUpstream(
        currentMap: OriginMap?,
        rel: String,
        caretKey: String,
    ): String = currentMap?.getRenameOldName(rel, caretKey) ?: caretKey

    /**
     * Emits each version, in order from [currentIdx]-1 down to 0, whose entry for the
     * tracked member (after following the rename chain) matches [filter]. See the class
     * docs for filter semantics. Null entries in [maps] are skipped (gap, keep walking).
     */
    fun walkMemberUpstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        filter: Set<OriginFlag> = emptySet(),
    ): Sequence<TrueSrcMemberHit> = sequence {
        var key = normalizeCaretKeyForUpstream(maps.getOrNull(currentIdx), rel, caretKey)
        for (i in (currentIdx - 1) downTo 0) {
            val map = maps[i] ?: continue
            val renamedFrom = map.getRenameOldName(rel, key)
            if (renamedFrom != null) {
                if (map.matchesMember(rel, key, filter)) {
                    yield(TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key)))
                }
                key = renamedFrom
                continue
            }
            if (map.matchesMember(rel, key, filter)) {
                yield(TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key)))
            }
        }
    }

    /**
     * Symmetric downstream walk. The caret key is used directly (no normalization);
     * a downstream rename would be discovered at the next version's map via
     * `!renamed#key`.
     */
    fun walkMemberDownstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        filter: Set<OriginFlag> = emptySet(),
    ): Sequence<TrueSrcMemberHit> = sequence {
        var key = caretKey
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            val renamedTo = map.getRenameNewName(rel, key)
            if (renamedTo != null) {
                if (map.matchesMember(rel, renamedTo, filter)) {
                    yield(TrueSrcMemberHit(i, renamedTo, map.getMemberFlags(rel, renamedTo)))
                }
                key = renamedTo
                continue
            }
            if (map.matchesMember(rel, key, filter)) {
                yield(TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key)))
            }
        }
    }

    /**
     * Nearest matching hit in [direction]. Written as a dedicated early-exit loop rather
     * than a `firstOrNull()` on the walk sequence so callers that only want the nearest
     * don't pay for the full traversal.
     */
    fun nearestMember(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        direction: Int,
        filter: Set<OriginFlag> = emptySet(),
    ): TrueSrcMemberHit? {
        if (direction == 0) return null
        if (direction < 0) {
            var key = normalizeCaretKeyForUpstream(maps.getOrNull(currentIdx), rel, caretKey)
            for (i in (currentIdx - 1) downTo 0) {
                val map = maps[i] ?: continue
                val renamedFrom = map.getRenameOldName(rel, key)
                if (renamedFrom != null) {
                    if (map.matchesMember(rel, key, filter)) {
                        return TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key))
                    }
                    key = renamedFrom
                    continue
                }
                if (map.matchesMember(rel, key, filter)) {
                    return TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key))
                }
            }
            return null
        }
        var key = caretKey
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            val renamedTo = map.getRenameNewName(rel, key)
            if (renamedTo != null) {
                if (map.matchesMember(rel, renamedTo, filter)) {
                    return TrueSrcMemberHit(i, renamedTo, map.getMemberFlags(rel, renamedTo))
                }
                key = renamedTo
                continue
            }
            if (map.matchesMember(rel, key, filter)) {
                return TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key))
            }
        }
        return null
    }

    /**
     * Boolean shortcut over [nearestMember]. Distinct code path — doesn't allocate the
     * hit record — but shares the same rename + filter semantics.
     */
    fun hasMember(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        direction: Int,
        filter: Set<OriginFlag> = emptySet(),
    ): Boolean {
        if (direction == 0) return false
        if (direction < 0) {
            var key = normalizeCaretKeyForUpstream(maps.getOrNull(currentIdx), rel, caretKey)
            for (i in (currentIdx - 1) downTo 0) {
                val map = maps[i] ?: continue
                val renamedFrom = map.getRenameOldName(rel, key)
                if (renamedFrom != null) {
                    if (map.matchesMember(rel, key, filter)) return true
                    key = renamedFrom
                    continue
                }
                if (map.matchesMember(rel, key, filter)) return true
            }
            return false
        }
        var key = caretKey
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            val renamedTo = map.getRenameNewName(rel, key)
            if (renamedTo != null) {
                if (map.matchesMember(rel, renamedTo, filter)) return true
                key = renamedTo
                continue
            }
            if (map.matchesMember(rel, key, filter)) return true
        }
        return false
    }

    /**
     * Aggregated bidirectional view for the Alt+Shift+V popup. Always enumerates every
     * version — the popup "shows all versions" regardless of flag filtering, which belongs
     * to the gutter / keybind paths only.
     */
    fun allMemberVersions(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
    ): List<MemberVersionView> {
        val result = arrayOfNulls<MemberVersionView>(maps.size)

        // Current version: use caret key as-is.
        result[currentIdx] = resolveMemberView(maps[currentIdx], currentIdx, rel, caretKey)

        // Track key per direction to follow rename chains.
        var upKey = normalizeCaretKeyForUpstream(maps.getOrNull(currentIdx), rel, caretKey)
        for (i in (currentIdx - 1) downTo 0) {
            val map = maps[i]
            if (map == null) { result[i] = MemberVersionView.Absent(i); continue }
            val renamedFrom = map.getRenameOldName(rel, upKey)
            result[i] = resolveMemberView(map, i, rel, upKey)
            if (renamedFrom != null) upKey = renamedFrom
        }

        var downKey = caretKey
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i]
            if (map == null) { result[i] = MemberVersionView.Absent(i); continue }
            val renamedTo = map.getRenameNewName(rel, downKey)
            if (renamedTo != null) {
                result[i] = resolveMemberView(map, i, rel, renamedTo)
                downKey = renamedTo
            } else {
                result[i] = resolveMemberView(map, i, rel, downKey)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return (result as Array<MemberVersionView>).toList()
    }

    private fun resolveMemberView(
        map: OriginMap?,
        versionIdx: Int,
        rel: String,
        key: String,
    ): MemberVersionView {
        if (map == null) return MemberVersionView.Absent(versionIdx)
        val hasEntry = map.getMember(rel, key) != null
        if (!hasEntry) return MemberVersionView.Absent(versionIdx)
        val flags = map.getMemberFlags(rel, key)
        return if (flags.contains(OriginFlag.TRUESRC)) {
            MemberVersionView.TrueSrc(versionIdx, key, flags)
        } else {
            MemberVersionView.Inherited(versionIdx, key)
        }
    }

    // --- Class walks ---------------------------------------------------------

    fun walkClassUpstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        filter: Set<OriginFlag> = emptySet(),
    ): Sequence<TrueSrcClassHit> = sequence {
        for (i in (currentIdx - 1) downTo 0) {
            val map = maps[i] ?: continue
            if (map.matchesFile(rel, filter)) {
                yield(TrueSrcClassHit(i, map.getFlags(rel)))
            }
        }
    }

    fun walkClassDownstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        filter: Set<OriginFlag> = emptySet(),
    ): Sequence<TrueSrcClassHit> = sequence {
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            if (map.matchesFile(rel, filter)) {
                yield(TrueSrcClassHit(i, map.getFlags(rel)))
            }
        }
    }

    fun nearestClass(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        direction: Int,
        filter: Set<OriginFlag> = emptySet(),
    ): TrueSrcClassHit? {
        if (direction == 0) return null
        val range = if (direction < 0) (currentIdx - 1) downTo 0 else (currentIdx + 1) until maps.size
        for (i in range) {
            val map = maps[i] ?: continue
            if (map.matchesFile(rel, filter)) return TrueSrcClassHit(i, map.getFlags(rel))
        }
        return null
    }

    fun hasClass(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        direction: Int,
        filter: Set<OriginFlag> = emptySet(),
    ): Boolean {
        if (direction == 0) return false
        val range = if (direction < 0) (currentIdx - 1) downTo 0 else (currentIdx + 1) until maps.size
        for (i in range) {
            val map = maps[i] ?: continue
            if (map.matchesFile(rel, filter)) return true
        }
        return false
    }

    fun allClassVersions(
        maps: List<OriginMap?>,
        rel: String,
    ): List<ClassVersionView> = maps.mapIndexed { i, map ->
        when {
            map == null -> ClassVersionView.Absent(i)
            map.isFileInTrueSrc(rel) -> ClassVersionView.TrueSrc(i, map.getFlags(rel))
            map.getFile(rel) != null -> ClassVersionView.Inherited(i)
            else -> ClassVersionView.Absent(i)
        }
    }
}
