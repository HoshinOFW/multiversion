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
 */
object OriginNavigation {

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
            val originValue: String,
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
            val originValue: String,
        ) : ClassVersionView
        data class Absent(override val versionIdx: Int) : ClassVersionView
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
     * Emits each version, in order from [currentIdx]-1 down to 0, where the tracked
     * member (following rename chains) is declared in trueSrc. Does not include the
     * current version. Null entries in [maps] are skipped (gap, keep walking).
     */
    fun walkMemberUpstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
    ): Sequence<TrueSrcMemberHit> = sequence {
        var key = normalizeCaretKeyForUpstream(maps.getOrNull(currentIdx), rel, caretKey)
        for (i in (currentIdx - 1) downTo 0) {
            val map = maps[i] ?: continue
            val renamedFrom = map.getRenameOldName(rel, key)
            if (renamedFrom != null) {
                // Version i has the member declared under `key` via @ModifySignature,
                // renamed from an older descriptor. Emit under `key`, then continue
                // upstream under the older descriptor.
                yield(TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key)))
                key = renamedFrom
                continue
            }
            if (map.isMemberInTrueSrc(rel, key)) {
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
    ): Sequence<TrueSrcMemberHit> = sequence {
        var key = caretKey
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            val renamedTo = map.getRenameNewName(rel, key)
            if (renamedTo != null) {
                yield(TrueSrcMemberHit(i, renamedTo, map.getMemberFlags(rel, renamedTo)))
                key = renamedTo
                continue
            }
            if (map.isMemberInTrueSrc(rel, key)) {
                yield(TrueSrcMemberHit(i, key, map.getMemberFlags(rel, key)))
            }
        }
    }

    fun nearestMember(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        direction: Int,
    ): TrueSrcMemberHit? = when {
        direction < 0 -> walkMemberUpstream(maps, currentIdx, rel, caretKey).firstOrNull()
        direction > 0 -> walkMemberDownstream(maps, currentIdx, rel, caretKey).firstOrNull()
        else -> null
    }

    fun hasTrueSrcMember(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        caretKey: String,
        direction: Int,
    ): Boolean = nearestMember(maps, currentIdx, rel, caretKey, direction) != null

    /**
     * Aggregated bidirectional view for the Alt+Shift+V popup.
     *
     * For every version 0 until maps.size, returns a [MemberVersionView]:
     *   - [MemberVersionView.TrueSrc] if that version declares the tracked member in
     *     trueSrc (following the rename chain from [currentIdx]).
     *   - [MemberVersionView.Inherited] if the map has an entry for the tracked key
     *     but TRUESRC is not set (member appears in patchedSrc purely by inheritance).
     *   - [MemberVersionView.Absent] if no entry at all (class does not contain the
     *     member in this version).
     *
     * The [currentIdx] entry is always included (read directly from maps[currentIdx]).
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
        val originValue = map.getMember(rel, key) ?: return MemberVersionView.Absent(versionIdx)
        val flags = map.getMemberFlags(rel, key)
        return if (flags.contains(OriginFlag.TRUESRC)) {
            MemberVersionView.TrueSrc(versionIdx, key, flags)
        } else {
            MemberVersionView.Inherited(versionIdx, key, originValue)
        }
    }

    // --- Class walks ---------------------------------------------------------

    fun walkClassUpstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
    ): Sequence<TrueSrcClassHit> = sequence {
        for (i in (currentIdx - 1) downTo 0) {
            val map = maps[i] ?: continue
            if (map.isFileInTrueSrc(rel)) {
                yield(TrueSrcClassHit(i, map.getFlags(rel)))
            }
        }
    }

    fun walkClassDownstream(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
    ): Sequence<TrueSrcClassHit> = sequence {
        for (i in (currentIdx + 1) until maps.size) {
            val map = maps[i] ?: continue
            if (map.isFileInTrueSrc(rel)) {
                yield(TrueSrcClassHit(i, map.getFlags(rel)))
            }
        }
    }

    fun nearestClass(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        direction: Int,
    ): TrueSrcClassHit? = when {
        direction < 0 -> walkClassUpstream(maps, currentIdx, rel).firstOrNull()
        direction > 0 -> walkClassDownstream(maps, currentIdx, rel).firstOrNull()
        else -> null
    }

    fun hasTrueSrcClass(
        maps: List<OriginMap?>,
        currentIdx: Int,
        rel: String,
        direction: Int,
    ): Boolean = nearestClass(maps, currentIdx, rel, direction) != null

    fun allClassVersions(
        maps: List<OriginMap?>,
        rel: String,
    ): List<ClassVersionView> = maps.mapIndexed { i, map ->
        when {
            map == null -> ClassVersionView.Absent(i)
            map.isFileInTrueSrc(rel) -> ClassVersionView.TrueSrc(i, map.getFlags(rel))
            map.getFile(rel) != null -> ClassVersionView.Inherited(i, map.getFile(rel)!!)
            else -> ClassVersionView.Absent(i)
        }
    }
}
