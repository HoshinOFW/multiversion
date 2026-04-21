package com.github.hoshinofw.multiversion.engine

import java.io.File

/**
 * In-memory representation of {@code @ModifyClass} routing for a single patched version.
 *
 * Maps target class rel paths (e.g. `com/example/Foo.java`) to the list of modifier
 * file rel paths that target them. Persisted as one plain-text sidecar per target,
 * living next to the merged class in patchedSrc:
 *
 * ```
 * # build/patchedSrc/main/java/com/example/Foo.java.routing
 * com/example/Foo.java
 * com/example/FooBehaviorPatch.java
 * com/example/FooNetworkPatch.java
 * ```
 *
 * Lines are sorted alphabetically. The sidecar is written only for targets whose
 * modifier set is not a single same-named file (i.e. not pure implicit routing);
 * callers fall back to same-name lookup when no sidecar exists.
 *
 * Owned by the merge engine in the same spirit as [OriginMap]: all read/write/query
 * lives here, consumers call through this API rather than parsing sidecars directly.
 */
class ClassRoutingMap() {

    private val targetToModifiers = LinkedHashMap<String, MutableList<String>>()
    private val modifierToTarget = LinkedHashMap<String, String>()

    companion object {
        /** File suffix for per-target routing sidecars. */
        const val SIDECAR_SUFFIX = ".routing"

        /**
         * Scans [patchedJavaOutDir] (typically `build/patchedSrc/main/java`) for
         * `*.java.routing` sidecars and returns an aggregate routing map.
         * Missing or unreadable sidecars are silently skipped.
         */
        @JvmStatic
        fun fromPatchedSrcDir(patchedJavaOutDir: File): ClassRoutingMap {
            val map = ClassRoutingMap()
            if (!patchedJavaOutDir.isDirectory) return map
            patchedJavaOutDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".java$SIDECAR_SUFFIX") }
                .forEach { sidecar ->
                    val targetRel = PathUtil.relativize(patchedJavaOutDir, sidecar)
                        .removeSuffix(SIDECAR_SUFFIX)
                    val modifiers = try {
                        sidecar.readLines(Charsets.UTF_8)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (modifiers.isNotEmpty()) map.addRoutes(targetRel, modifiers)
                }
            return map
        }
    }

    /**
     * Per-target sidecar I/O for callers that need to mutate one target's routing without
     * loading the whole module's routing view. Used by [MergeEngine.siblingGroupUpdatePatchedSrc]
     * (file-level merge writes one target's sidecar) and by IDE refactor plumbing (move /
     * rename shifts sidecar filenames or modifier lines without touching merge state).
     *
     * Engine-owned: consumers call these helpers instead of constructing sidecar file paths
     * themselves, so the wire format and placement stay encapsulated here. Mirrors the
     * [OriginMap] I/O pattern (callers don't know the TSV format, they call into `OriginMap`).
     */
    object Sidecars {
        /**
         * Writes the sidecar for [targetRel] under [patchedJavaOutDir]. The modifier list is
         * sorted alphabetically before writing. If the set is a single same-named modifier
         * (pure implicit routing), any existing sidecar is deleted so the filesystem state
         * stays consistent with [writeSidecars]' filter.
         */
        @JvmStatic
        fun writeOne(patchedJavaOutDir: File, targetRel: String, modifierRels: Collection<String>) {
            val sidecar = File(patchedJavaOutDir, "$targetRel$SIDECAR_SUFFIX")
            val sorted = modifierRels.distinct().sorted()
            if (sorted.isEmpty() || (sorted.size == 1 && sorted[0] == targetRel)) {
                if (sidecar.exists()) sidecar.delete()
                return
            }
            sidecar.parentFile?.mkdirs()
            sidecar.writeText(sorted.joinToString("\n") + "\n", Charsets.UTF_8)
        }

        /**
         * Moves a sidecar from `oldTargetRel.routing` to `newTargetRel.routing` under
         * [patchedJavaOutDir]. No-op if there's no sidecar at the old location. Called by
         * IDE refactor plumbing when a target class is renamed or moved.
         */
        @JvmStatic
        fun renameTarget(patchedJavaOutDir: File, oldTargetRel: String, newTargetRel: String) {
            if (oldTargetRel == newTargetRel) return
            val oldSidecar = File(patchedJavaOutDir, "$oldTargetRel$SIDECAR_SUFFIX")
            if (!oldSidecar.exists()) return
            val newSidecar = File(patchedJavaOutDir, "$newTargetRel$SIDECAR_SUFFIX")
            newSidecar.parentFile?.mkdirs()
            if (!oldSidecar.renameTo(newSidecar)) {
                oldSidecar.copyTo(newSidecar, overwrite = true)
                oldSidecar.delete()
            }
        }

        /**
         * Rewrites every sidecar under [patchedJavaOutDir] whose modifier list contains
         * [oldModifierRel], replacing that entry with [newModifierRel]. Called by IDE
         * refactor plumbing when a modifier file's rel path changes (rename or move).
         */
        @JvmStatic
        fun renameModifier(patchedJavaOutDir: File, oldModifierRel: String, newModifierRel: String) {
            if (oldModifierRel == newModifierRel) return
            if (!patchedJavaOutDir.isDirectory) return
            patchedJavaOutDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".java$SIDECAR_SUFFIX") }
                .forEach { sidecar ->
                    val original = try { sidecar.readText(Charsets.UTF_8) } catch (_: Exception) { return@forEach }
                    val updated = original.lineSequence().joinToString("\n") { line ->
                        if (line.trim() == oldModifierRel) newModifierRel else line
                    }
                    if (updated != original) sidecar.writeText(updated, Charsets.UTF_8)
                }
        }
    }

    // ---- Mutation ----

    /**
     * Adds a single modifier for [targetRel]. Duplicates are ignored. Modifier lists are
     * kept sorted alphabetically in memory so [getModifiers] ordering matches the on-disk
     * sidecar ordering (see [writeSidecars]), which is what the engine's sibling-index
     * encoding in `_originMap.tsv` values relies on.
     */
    fun addRoute(targetRel: String, modifierRel: String) {
        val list = targetToModifiers.getOrPut(targetRel) { mutableListOf() }
        if (modifierRel !in list) {
            val insertAt = list.binarySearch(modifierRel).let { if (it < 0) -it - 1 else it }
            list.add(insertAt, modifierRel)
        }
        modifierToTarget[modifierRel] = targetRel
    }

    /** Adds multiple modifiers for [targetRel] in one call. Duplicates are ignored. */
    fun addRoutes(targetRel: String, modifierRels: Collection<String>) {
        modifierRels.forEach { addRoute(targetRel, it) }
    }

    // ---- Query ----

    /**
     * Returns all modifier rel paths targeting [targetRel] in alphabetical order, or empty
     * list if none. The order matches the on-disk sidecar so the engine's sibling-index
     * values resolve back to the same rel path.
     */
    fun getModifiers(targetRel: String): List<String> =
        targetToModifiers[targetRel].orEmpty().toList()

    /**
     * Alphabetical index of [modifierRel] inside the sibling list of [targetRel], or -1 if
     * [modifierRel] is not a registered modifier of [targetRel]. Used by the engine to
     * encode a member's origin as `V:S:L:C` where `S` is this index.
     */
    fun indexOfModifier(targetRel: String, modifierRel: String): Int =
        targetToModifiers[targetRel]?.indexOf(modifierRel) ?: -1

    /**
     * Alphabetical lookup of the modifier at [index] inside [targetRel]'s sibling list,
     * or null if the index is out of bounds or the target has no routing entry. Used by
     * the resolver to expand a member's sibling index back to the real source rel.
     */
    fun modifierAtIndex(targetRel: String, index: Int): String? =
        targetToModifiers[targetRel]?.getOrNull(index)

    /** Returns the target rel path for [modifierRel], or null if it is not a modifier. */
    fun getTarget(modifierRel: String): String? = modifierToTarget[modifierRel]

    /** Returns true if [targetRel] has any modifiers in this map. */
    fun hasRoutesFor(targetRel: String): Boolean =
        targetToModifiers[targetRel]?.isNotEmpty() == true

    /** Returns true if [modifierRel] is recorded as a modifier. */
    fun isModifier(modifierRel: String): Boolean = modifierToTarget.containsKey(modifierRel)

    /** All target rel paths present in this map. */
    fun targets(): Set<String> = targetToModifiers.keys.toSet()

    /** All modifier rel paths present in this map. */
    fun modifiers(): Set<String> = modifierToTarget.keys.toSet()

    val size: Int get() = targetToModifiers.size

    fun isEmpty(): Boolean = targetToModifiers.isEmpty()

    // ---- Sidecar I/O ----

    /**
     * Writes per-target sidecars into [patchedJavaOutDir].
     *
     * For each target, writes `<targetRel>$SIDECAR_SUFFIX` next to the merged class,
     * containing the alphabetically-sorted modifier rel paths, one per line.
     *
     * Skips targets whose only modifier is the same-named file (pure implicit
     * routing); those never need a sidecar. Parent directories are created on demand.
     */
    fun writeSidecars(patchedJavaOutDir: File) {
        if (!patchedJavaOutDir.exists()) patchedJavaOutDir.mkdirs()
        for ((targetRel, modifiers) in targetToModifiers) {
            val sorted = modifiers.sorted()
            if (sorted.size == 1 && sorted[0] == targetRel) continue

            val sidecar = File(patchedJavaOutDir, "$targetRel$SIDECAR_SUFFIX")
            sidecar.parentFile?.mkdirs()
            sidecar.writeText(sorted.joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    /**
     * Removes any `*.java$SIDECAR_SUFFIX` file under [patchedJavaOutDir] that does not
     * correspond to a target in this map. Useful during full regeneration when an
     * older generation left stale sidecars behind.
     */
    fun pruneStaleSidecars(patchedJavaOutDir: File) {
        if (!patchedJavaOutDir.isDirectory) return
        patchedJavaOutDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java$SIDECAR_SUFFIX") }
            .forEach { sidecar ->
                val targetRel = PathUtil.relativize(patchedJavaOutDir, sidecar)
                    .removeSuffix(SIDECAR_SUFFIX)
                if (!hasRoutesFor(targetRel)) sidecar.delete()
            }
    }
}
