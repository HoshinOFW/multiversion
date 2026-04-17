package com.github.hoshinofw.multiversion.engine

/**
 * Result of resolving where a member or file in patchedSrc actually came from.
 *
 * @property originPath  Relative path from the project root to the source file
 *                       (e.g., "1.21.1/common/src/main/java/com/Foo.java").
 * @property line        1-based line number within [originPath], or 0 if file-level only.
 * @property col         1-based column within [line], or 0 if not available.
 */
data class ResolvedOrigin(
    val originPath: String,
    val line: Int,
    val col: Int,
)

/**
 * Wraps an [OriginMap] and provides always-non-null origin resolution by applying
 * the "missing = base version" rule.
 *
 * With complete origin maps (generated with base map inheritance), the fallback
 * should rarely trigger. It exists as a safety net for edge cases where the origin
 * map was not generated with inheritance (e.g., first version, or pre-inheritance maps).
 */
class OriginResolver(
    private val originMap: OriginMap,
    private val baseRelRoot: String,
) {
    /**
     * Resolves where a specific member in [rel] came from. Always returns non-null.
     *
     * Resolution order:
     * 1. Member-level entry in origin map -> explicit origin with line+col
     * 2. File-level entry in origin map   -> explicit origin, line/col 0
     * 3. Neither present                  -> base version fallback
     *
     * @param rel        File path relative to patchedSrc java root (e.g., "com/example/Foo.java")
     * @param memberKey  Member descriptor (e.g., "bar(int,String)", "<init>(int)", "myField")
     */
    fun resolveMember(rel: String, memberKey: String): ResolvedOrigin {
        val memberRaw = originMap.getMember(rel, memberKey)
        if (memberRaw != null) {
            val (path, line, col) = OriginMap.parseValue(memberRaw)
            return ResolvedOrigin(path, line, col)
        }
        return resolveFile(rel)
    }

    /**
     * Resolves where a whole file in patchedSrc came from. Always returns non-null.
     *
     * Resolution order:
     * 1. File-level entry in origin map -> explicit origin
     * 2. Not present                    -> base version fallback
     */
    fun resolveFile(rel: String): ResolvedOrigin {
        val fileRaw = originMap.getFile(rel)
        if (fileRaw != null) {
            val (path, line, col) = OriginMap.parseValue(fileRaw)
            return ResolvedOrigin(path, line, col)
        }
        return ResolvedOrigin("$baseRelRoot/$rel", 0, 0)
    }

    /**
     * Resolves origins for all members in a file. Returns a map from member descriptor
     * to [ResolvedOrigin]. Excludes rename tracking entries.
     */
    fun resolveAllMembers(rel: String): Map<String, ResolvedOrigin> {
        return originMap.getMembersForFile(rel).mapValues { (_, raw) ->
            val (path, line, col) = OriginMap.parseValue(raw)
            ResolvedOrigin(path, line, col)
        }
    }
}
