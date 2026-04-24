package com.github.hoshinofw.multiversion.engine

class MergeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One per-rel failure recorded by a resilient merge entry. Collected into the result of
 * [MergeEngine.versionUpdatePatchedSrc] and the cascade entries instead of thrown, so
 * "everything except what failed still finalizes."
 *
 * [phase] identifies which loop produced the failure for human-readable logging; no
 * behavioral branching keys off it. [cause] is `Throwable` (not `Exception`) because
 * JavaParser can throw `AssertionError` on malformed input.
 */
data class MergeFailure(
    val rel: String,
    val versionIdx: Int,
    val phase: Phase,
    val cause: Throwable,
) {
    enum class Phase {
        /** Sibling-group validation in [ModifyClassPreMerge]. */
        PRE_MERGE,
        /** Plain-file merge in [True2PatchMergeEngine.processVersion]'s plain-file loop or in the cascade single-file path. */
        PLAIN_FILE,
        /** Virtual-target (sibling-group) merge in [True2PatchMergeEngine.processVersion] or in the cascade group path. */
        VIRTUAL_TARGET,
        /** Inherited-origin walk for files not in trueSrc. */
        INHERITED_ORIGIN,
        /** Routing sidecar write/prune, orphan-modifier delete. */
        SIDECAR_IO,
        /**
         * Cascade skipped this version's merge of a rel because the same rel failed upstream
         * in this same cascade. [cause] is the upstream failure's `Throwable`. The check is
         * isolated to a single helper inside the cascade entry so swapping the in-memory
         * containment set for a persistent `OriginFlag.FAILED` lookup later is a one-line change.
         */
        SKIPPED_UPSTREAM_FAILED,
    }
}
