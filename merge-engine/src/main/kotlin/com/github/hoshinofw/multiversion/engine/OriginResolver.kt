package com.github.hoshinofw.multiversion.engine

/**
 * Result of resolving where a member or file in patchedSrc actually came from.
 *
 * @property originPath  Relative path from the project root to the source file
 *                       (e.g. `1.21.1/common/src/main/java/com/Foo.java`).
 * @property line        1-based line number within [originPath], or 0 if file-level only.
 * @property col         1-based column within [line], or 0 if not available.
 */
data class ResolvedOrigin(
    val originPath: String,
    val line: Int,
    val col: Int,
)

/**
 * Wraps an [OriginMap] and provides always-non-null origin resolution by expanding
 * [CompactPos] positions against the project's version list and per-version routing.
 *
 * The compact wire format encodes `V` (version index) and `S` (sibling index) instead of
 * a full versioned path, so the resolver needs a few pieces of project context to expand
 * back to a path:
 *
 * - [versions]      Ordered version directory names (e.g. `["1.20.1", "1.21.1"]`). Indexed
 *                   by `V` in every [CompactPos].
 * - [moduleName]    The patch module this resolver serves (e.g. `common`, `fabric`,
 *                   `1.21.1_neoforge`). Used to build trueSrc rel-roots.
 * - [routingFor]    Callback returning the per-version [ClassRoutingMap]; used to expand
 *                   `(target, S)` to a real modifier rel. Returning null (no routing)
 *                   means "no `@ModifyClass` group for this version" and the target rel
 *                   is used as-is.
 *
 * Fallback (`resolveMember` / `resolveFile` with no entry) uses a synthesized path at the
 * owning module's trueSrc root; see [baseRelRootFallback]. The fallback only kicks in
 * when the map is incomplete (e.g. a member was never tracked) and is a best-effort safety
 * net rather than a load-bearing path.
 */
class OriginResolver @JvmOverloads constructor(
    private val originMap: OriginMap,
    private val versions: List<String>,
    private val moduleName: String,
    private val routingFor: (Int) -> ClassRoutingMap? = { null },
    private val baseRelRootFallback: String = "",
) {

    /**
     * Resolves where a specific member in [rel] came from. Always returns non-null.
     *
     * Resolution order:
     * 1. Member body position in origin map -> explicit origin expanded from `V:S:L:C`.
     * 2. File origin in origin map          -> explicit origin expanded from `V:S`.
     * 3. Neither present                    -> fallback under [baseRelRootFallback].
     */
    fun resolveMember(rel: String, memberKey: String): ResolvedOrigin {
        val body = originMap.getMemberBody(rel, memberKey)
        if (body != null) return expand(rel, body)
        return resolveFile(rel)
    }

    /**
     * Resolves the declaration position of a member in this version's trueSrc, or null if
     * the member does not carry a decl column (i.e. it is not `@ModifySignature`,
     * `@OverwriteVersion`, or `NEW` in its owning version, which is where decl columns are
     * emitted). The [currentVersionIdx] argument fills in the missing `V`; decl columns
     * always refer to the owning map's own version.
     */
    fun resolveMemberDeclaration(rel: String, memberKey: String, currentVersionIdx: Int): ResolvedOrigin? {
        val decl = originMap.getMemberDecl(rel, memberKey) ?: return null
        val effective = CompactPos(currentVersionIdx, decl.s, decl.line, decl.col)
        return expand(rel, effective)
    }

    /**
     * Resolves where a whole file in patchedSrc came from. Always returns non-null.
     *
     * Resolution order:
     * 1. File origin in origin map -> explicit origin expanded from `V:S`.
     * 2. Not present               -> fallback under [baseRelRootFallback].
     */
    fun resolveFile(rel: String): ResolvedOrigin {
        val origin = originMap.getFileOrigin(rel)
        if (origin != null) return expand(rel, origin)
        val path = if (baseRelRootFallback.isEmpty()) rel else "$baseRelRootFallback/$rel"
        return ResolvedOrigin(path, 0, 0)
    }

    /**
     * Resolves origins for all members in a file, excluding rename tracking entries.
     */
    fun resolveAllMembers(rel: String): Map<String, ResolvedOrigin> {
        val members = originMap.getMembersForFile(rel)
        val out = LinkedHashMap<String, ResolvedOrigin>(members.size)
        for ((member, raw) in members) {
            val pos = OriginMap.parseBody(raw)
            if (pos != null) out[member] = expand(rel, pos)
        }
        return out
    }

    /**
     * Expands a [CompactPos] to a concrete `(path, line, col)` triple. [targetRel] is the
     * class rel path the position refers to; the [CompactPos.s] index is resolved against
     * that target's routing for the given version.
     */
    private fun expand(targetRel: String, pos: CompactPos): ResolvedOrigin {
        val versionName = versions.getOrNull(pos.v)
        if (versionName == null) {
            // V is out of range: best-effort fallback to the target rel under the configured base.
            val fallbackRoot = if (baseRelRootFallback.isEmpty()) "" else "$baseRelRootFallback/"
            return ResolvedOrigin("$fallbackRoot$targetRel", pos.line, pos.col)
        }
        val routing = routingFor(pos.v)
        val siblingRel = routing?.modifierAtIndex(targetRel, pos.s) ?: targetRel
        val path = "$versionName/$moduleName/${PathUtil.TRUE_SRC_MARKER}/$siblingRel"
        return ResolvedOrigin(path, pos.line, pos.col)
    }
}
