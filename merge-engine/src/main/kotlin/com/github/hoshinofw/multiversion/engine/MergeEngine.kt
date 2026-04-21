package com.github.hoshinofw.multiversion.engine

import java.io.File

object MergeEngine {

    // ---- Content-based API ----

    /**
     * Pure content merge: (content?, content?) -> MergeResult.
     * Null content means the file is absent.
     */
    fun mergeFileContent(
        currentContent: String?,
        baseContent: String?,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult = True2PatchMergeEngine.mergeContent(
        currentContent, baseContent, rel, versionIdx, baseVersionIdx, baseOriginMap
    )

    /**
     * Content in, file out: merges and writes the result to [outFile].
     * Returns the MergeResult (including origin entries).
     */
    fun mergeFileContentToFile(
        currentContent: String?,
        baseContent: String?,
        outFile: File,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        val result = True2PatchMergeEngine.mergeContent(
            currentContent, baseContent, rel, versionIdx, baseVersionIdx, baseOriginMap
        )
        writeResult(result, outFile)
        return result
    }

    /**
     * File in, content out: reads files from disk, merges, returns result without writing.
     */
    fun mergeFileFromFiles(
        currentFile: File,
        baseFile: File,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult = True2PatchMergeEngine.mergeContent(
        if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null,
        if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null,
        rel, versionIdx, baseVersionIdx, baseOriginMap
    )

    /**
     * File in, file out, with optional origin map patching.
     * Existing API used by the IDE plugin.
     */
    fun fileUpdatePatchedSrc(
        currentFile: File,
        baseFile: File,
        outFile: File,
        rel: String,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMapFile: File? = null,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentContent = if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null
        val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null
        val result = mergeFileContentToFile(
            currentContent, baseContent, outFile, rel, versionIdx, baseVersionIdx, baseOriginMap
        )
        if (originMapFile != null) {
            val map = OriginMap.fromFile(originMapFile)
            map.patchFile(rel, result.originEntries)
            map.toFile(originMapFile)
        }
    }

    // ---- Batch API ----

    /**
     * Walks [currentSrcDir] and merges every `.java` file into [patchedOutDir],
     * using [baseDir] as the accumulated base for this version step.
     *
     * [versionIdx] is this version's 0-based index in the project's version list; emitted
     * origins use it so the resolver can expand back to a path at read time. [baseVersionIdx]
     * is the base version's index (typically `versionIdx - 1`).
     */
    @JvmStatic
    fun versionUpdatePatchedSrc(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) = True2PatchMergeEngine.processVersion(
        currentSrcDir, baseDir, patchedOutDir, versionIdx, baseVersionIdx, originMap, baseOriginMap
    )

    // ---- Origin map synthesis ----

    /** A single file that failed synthesis, with the cause. */
    data class SynthesisFailure(val rel: String, val cause: Throwable)

    /**
     * Result of [synthesizeFromTrueSrc]: the built map plus any per-file failures that
     * were tolerated. When `tolerateParseErrors = false` on the call site, [failures] is
     * always empty because the first failure aborts with a [MergeException].
     */
    data class SynthesisResult(
        val map: OriginMap,
        val failures: List<SynthesisFailure>,
    )

    /**
     * Builds an in-memory [OriginMap] by walking a trueSrc directory and running the
     * single-file merge with no base, so every member is recorded as a brand-new
     * trueSrc declaration. Emits origins in the same compact v2 format as a normal
     * version merge, so the resulting map is interchangeable with a generated
     * `_originMap.tsv`.
     *
     * Primary users:
     * - [baseVersionUpdatePatchedSrc] calls this with `tolerateParseErrors = false` to
     *   fail the Gradle build on any unparseable trueSrc file.
     * - The IDE's cache calls this with `tolerateParseErrors = true` for versions that
     *   have a trueSrc directory but no generated `_originMap.tsv` (unbuilt patched
     *   versions), where tolerance is needed because a file may be mid-edit.
     *
     * @param trueSrcDir            the `<version>/<module>/src/main/java` directory.
     * @param versionIdx            the 0-based index of this version in the project's
     *                              version list; written into every origin entry's `V` field.
     * @param tolerateParseErrors   when true, parse / merge failures per file are collected
     *                              into [SynthesisResult.failures] instead of thrown so the
     *                              overall synthesis still produces a usable partial map.
     *                              When false (the recommended Gradle-build default), the
     *                              first failure aborts with a [MergeException].
     */
    @JvmOverloads
    fun synthesizeFromTrueSrc(
        trueSrcDir: File,
        versionIdx: Int,
        tolerateParseErrors: Boolean = true,
    ): SynthesisResult {
        val map = OriginMap()
        val failures = mutableListOf<SynthesisFailure>()
        if (!trueSrcDir.exists()) return SynthesisResult(map, failures)
        trueSrcDir.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.forEach { file ->
            val rel = PathUtil.relativize(trueSrcDir, file)
            try {
                val content = file.readText(Charsets.UTF_8)
                val result = True2PatchMergeEngine.mergeContent(
                    currentContent = content,
                    baseContent = null,
                    rel = rel,
                    versionIdx = versionIdx,
                    baseVersionIdx = versionIdx,  // no base: keep V stable for fallback math
                    baseOriginMap = null,
                )
                map.addEntries(result.originEntries)
            } catch (e: Throwable) {
                // Throwable: JavaParser can throw AssertionError on malformed input, which
                // slips past `Exception`-only catches.
                if (!tolerateParseErrors) {
                    throw MergeException("Synthesis failed for $rel: ${e.message}", e)
                }
                failures += SynthesisFailure(rel, e)
            }
        }
        return SynthesisResult(map, failures)
    }

    /**
     * Initializes the base (oldest) version's origin state on disk. The base version has
     * no upstream to merge against, so there is no patchedSrc Java output — its trueSrc is
     * already the compile source. This entry synthesises a v2 origin map from trueSrc and
     * writes it atomically to [originMapFile]. Also writes permissive `@ModifyClass`
     * routing sidecars under [patchedOutJavaDir] if any sibling groups exist (rare for
     * the base version but supported for completeness).
     *
     * Engine-owned: callers supply paths + the version index, the engine handles all
     * synthesis, wire-format emission, and filesystem writes. The Gradle plugin's
     * `generateBaseOriginMap` task and the IDE cache both call into this (or into
     * [synthesizeFromTrueSrc] for the in-memory unbuilt-version case).
     *
     * @return the [SynthesisResult] from the underlying synthesis. Callers (e.g. the
     *   Gradle task) can inspect [SynthesisResult.failures] and log or act on them.
     */
    @JvmStatic
    @JvmOverloads
    fun baseVersionUpdatePatchedSrc(
        trueSrcDir: File,
        patchedOutJavaDir: File,
        versionIdx: Int,
        originMapFile: File,
        tolerateParseErrors: Boolean = false,
    ): SynthesisResult {
        val synth = synthesizeFromTrueSrc(trueSrcDir, versionIdx, tolerateParseErrors)

        originMapFile.parentFile?.mkdirs()
        synth.map.toFileAtomic(originMapFile)

        val routing = ModifyClassPreMerge.synthesizeRoutingFromTrueSrc(trueSrcDir)
        if (!routing.isEmpty()) {
            patchedOutJavaDir.mkdirs()
            routing.writeSidecars(patchedOutJavaDir)
            routing.pruneStaleSidecars(patchedOutJavaDir)
        }

        return synth
    }

    /**
     * Permissive routing scan for a single trueSrc dir. Returns a [ClassRoutingMap]
     * of (target rel -> modifier rels) for every `@ModifyClass`-bearing file.
     *
     * No validation (no orphan / inner-class / one-overwrite / annotation-sync checks).
     * Used by the IDE cache as a fallback for versions without generated `.routing`
     * sidecars (unbuilt versions). Mirrors [synthesizeFromTrueSrc] for origin maps.
     */
    fun synthesizeRoutingFromTrueSrc(trueSrcDir: File): ClassRoutingMap =
        ModifyClassPreMerge.synthesizeRoutingFromTrueSrc(trueSrcDir)

    // ---- File-level `@ModifyClass` sibling-group merge ----

    /**
     * File-level pre-merge + merge for one `@ModifyClass` sibling group. Peer to
     * [mergeFileContentToFile] / [fileUpdatePatchedSrc] — takes in-memory sibling contents,
     * runs the virtual pre-merge, merges the virtual target against the base, writes the
     * merged class + patches the origin map + updates the routing sidecar. Used by the IDE
     * listeners for per-save incremental updates when the edited file participates in routing.
     *
     * **Scope.** Single version, single target group. Does not cascade to other versions;
     * callers that need cascade loop this entry per affected version. Treats [baseContent]
     * as up-to-date — this is the file-level peer of the full-version [versionUpdatePatchedSrc]
     * entry, which handles cross-version pre-merge, orphan / inner-class-target checks, and
     * bulk sidecar pruning. Do not use this for full builds.
     *
     * **Validation.** In-group only: one-overwrite, class-level annotation sync (for
     * `@OverwriteTypeDeclaration` / `@DeleteMethodsAndFields`), class-declaration sync under
     * `@OverwriteTypeDeclaration`, import simple-name collisions. Orphan and inner-class-target
     * checks are deferred to the full build + IDE inspections.
     *
     * **TRUESRC origin values** are rewritten so they point at the real sibling source file
     * + original line/col, not at the virtual target.
     */
    fun siblingGroupUpdatePatchedSrc(
        targetRel: String,
        siblingContents: Map<String, String>,
        baseContent: String?,
        patchedOutJavaDir: File,
        originMapFile: File,
        versionIdx: Int,
        baseVersionIdx: Int,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        val virtualTarget = ModifyClassPreMerge.buildVirtualTargetFromContents(targetRel, siblingContents)

        val outFile = File(patchedOutJavaDir, targetRel)
        val result = True2PatchMergeEngine.mergeVirtualTargetContent(
            virtualTarget, baseContent, outFile, targetRel,
            versionIdx, baseVersionIdx, baseOriginMap,
        )

        val originMap = OriginMap.fromFile(originMapFile)
        originMap.patchFile(targetRel, result.originEntries)
        originMap.toFileAtomic(originMapFile)

        ClassRoutingMap.Sidecars.writeOne(patchedOutJavaDir, targetRel, siblingContents.keys)

        return result
    }

    // ---- Result writing ----

    /**
     * Applies a [MergeResult] to a file: writes content for MERGED/COPIED_*,
     * deletes for DELETED, no-ops for SKIPPED.
     */
    fun writeResult(result: MergeResult, outFile: File) {
        when (result.action) {
            MergeAction.DELETED -> outFile.delete()
            MergeAction.SKIPPED -> { /* no-op */ }
            else -> {
                outFile.parentFile?.mkdirs()
                outFile.writeText(result.content!!, Charsets.UTF_8)
            }
        }
    }

    // ---- virtual src -> true src ----

    // TODO: implement virtual -> true pass (reverse engine).
    //  Direction: virtual src (what the user edits for a given version) -> true src (version-specific overlay files).
    //  A structural AST diff between the virtual view and the fully-merged patched src determines
    //  which members belong to the current version layer vs. a base layer, then writes only the delta back.

    fun versionUpdateTrueSrc(
        // Parameters TBD once the reverse engine design is finalised.
    ): Nothing = throw NotImplementedError("Reverse engine (virtual -> true src) is not yet implemented")

    fun fileUpdateTrueSrc(
        // Parameters TBD once the reverse engine design is finalised.
    ): Nothing = throw NotImplementedError("Reverse engine (virtual -> true src) is not yet implemented")
}
