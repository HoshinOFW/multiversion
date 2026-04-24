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
     * Result of [versionUpdatePatchedSrc]: the per-rel failures collected during the merge.
     * The map and routing sidecars on disk reflect "everything except what failed" — successful
     * files have correct PatchedSrc and origin entries, failed files are left in their prior
     * state. Empty list means a clean merge.
     */
    data class VersionMergeResult(val failures: List<MergeFailure>)

    /**
     * Walks [currentSrcDir] and merges every `.java` file into [patchedOutDir], using [baseDir]
     * as the accumulated base for this version step. Engine-owned: the engine writes
     * `_originMap.tsv` to [originMapFile] atomically as the final step (no caller I/O needed).
     *
     * Resilient: per-file failures are collected into the returned [VersionMergeResult.failures]
     * instead of thrown, so a single bad file doesn't kill the whole module's merge.
     *
     * [versionIdx] is this version's 0-based index in the project's version list; emitted
     * origins use it so the resolver can expand back to a path at read time. [baseVersionIdx]
     * is the base version's index (typically `versionIdx - 1`).
     */
    @JvmStatic
    @JvmOverloads
    fun versionUpdatePatchedSrc(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMapFile: File,
        baseOriginMap: OriginMap? = null,
    ): VersionMergeResult {
        val map = OriginMap()
        val failures = True2PatchMergeEngine.processVersion(
            currentSrcDir, baseDir, patchedOutDir,
            versionIdx, baseVersionIdx,
            map, baseOriginMap,
            originMapFile = originMapFile,
        )
        return VersionMergeResult(failures)
    }

    /**
     * Legacy version-level entry. The caller supplies a mutable [OriginMap] and writes it to
     * disk itself. To preserve the legacy "throw on failure" contract, this overload re-throws
     * the first per-file failure as a [MergeException]. Replaced by the
     * [VersionMergeResult]-returning overload above which is engine-I/O-owning and resilient.
     */
    @Deprecated(
        "Use the VersionMergeResult-returning overload that takes originMapFile and lets the engine own all artefact I/O.",
        ReplaceWith("versionUpdatePatchedSrc(currentSrcDir, baseDir, patchedOutDir, versionIdx, baseVersionIdx, originMapFile, baseOriginMap)"),
    )
    @JvmStatic
    fun versionUpdatePatchedSrc(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        versionIdx: Int,
        baseVersionIdx: Int,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) {
        val failures = True2PatchMergeEngine.processVersion(
            currentSrcDir, baseDir, patchedOutDir, versionIdx, baseVersionIdx, originMap, baseOriginMap
        )
        if (failures.isNotEmpty()) {
            val first = failures.first()
            throw MergeException("MergeEngine failed merging ${first.rel}: ${first.cause.message}", first.cause)
        }
    }

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

    /**
     * Result of [baseVersionFileUpdatePatchedSrc]: the files written to disk (for VFS refresh)
     * and per-file failures that were tolerated. The origin-map file is included in
     * [writtenFiles] whenever a new version of it is written, so IDE callers can trigger cache
     * invalidation on base-only projects the same way they do for downstream patchedSrc writes.
     */
    data class BaseFileUpdateResult(
        val writtenFiles: List<File>,
        val failures: List<MergeFailure>,
    )

    /**
     * File-level analogue of [baseVersionUpdatePatchedSrc]. Refreshes [originMapFile] for a
     * single edited rel in the base (oldest) version: no upstream to merge against, no
     * patchedSrc Java output — the base's trueSrc is already the compile source, the origin
     * map is the only artefact that needs updating.
     *
     * Behavior: load the existing map via [OriginMap.fromFile] (empty map if the file is
     * absent — fresh checkout tolerated, partial file gets written), remove any prior entries
     * for [editedRel], run a single-file no-base merge on [editedContent] via
     * [True2PatchMergeEngine.mergeContent] to produce fresh entries, patch the map, write it
     * atomically. [editedContent] = null means the file was deleted — entries are removed,
     * no merge runs. Engine owns all I/O.
     *
     * **@ModifyClass in base version is unsupported.** This entry always writes per-file
     * entries for [editedRel]; it does not resolve sibling groups or write Extension entries
     * against their Target. Matches what the full-version [baseVersionUpdatePatchedSrc] does
     * via the file-by-file [synthesizeFromTrueSrc] loop.
     */
    @JvmStatic
    @JvmOverloads
    fun baseVersionFileUpdatePatchedSrc(
        trueSrcDir: File,
        editedRel: String,
        editedContent: String?,
        versionIdx: Int,
        originMapFile: File,
        tolerateParseErrors: Boolean = false,
    ): BaseFileUpdateResult {
        val failures = mutableListOf<MergeFailure>()
        val map = OriginMap.fromFile(originMapFile)
        // Patch with empty entries clears every prior entry for this rel; the merge below
        // re-adds them when content is non-null. For deletions (content == null) this is the
        // final state.
        map.patchFile(editedRel, emptyList())

        if (editedContent != null) {
            try {
                val result = True2PatchMergeEngine.mergeContent(
                    currentContent = editedContent,
                    baseContent = null,
                    rel = editedRel,
                    versionIdx = versionIdx,
                    baseVersionIdx = versionIdx,
                    baseOriginMap = null,
                )
                map.patchFile(editedRel, result.originEntries)
            } catch (e: Throwable) {
                if (!tolerateParseErrors) {
                    throw MergeException("Base-version file merge failed for $editedRel: ${e.message}", e)
                }
                failures += MergeFailure(editedRel, versionIdx, MergeFailure.Phase.PLAIN_FILE, e)
            }
        }

        originMapFile.parentFile?.mkdirs()
        map.toFileAtomic(originMapFile)
        return BaseFileUpdateResult(writtenFiles = listOf(originMapFile), failures = failures)
    }

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

    // ---- File-level cascade across versions ----

    /**
     * Per-version data the cascade entries need. The IDE assembles this list from its
     * [EngineConfig] cache + routing cache; the engine works with [File] + plain data only.
     *
     * [baseOriginMapFile] points at the previous version's `_originMap.tsv` (or null for the
     * base version). The cascade re-loads it fresh per iteration, so a previous step's atomic
     * write is naturally picked up by the next downstream version.
     *
     * [routing] is THIS version's `@ModifyClass` routing — different versions can have
     * different routing for the same rel.
     *
     * [isBase] marks a base-version context. The base version has no upstream to merge against
     * and no patchedSrc Java output; the cascade dispatches such contexts to
     * [baseVersionFileUpdatePatchedSrc] instead of a single-file / sibling-group merge. Explicit
     * flag (not inferred from `baseOriginMapFile == null`) because a downstream version can
     * legitimately have no base map if upstream hasn't been built.
     */
    data class VersionContext(
        val versionIdx: Int,
        val baseVersionIdx: Int,
        val currentSrcDir: File,
        val baseDir: File,
        val patchedOutDir: File,
        val originMapFile: File,
        val routing: ClassRoutingMap,
        val baseOriginMapFile: File?,
        val isBase: Boolean = false,
    )

    /**
     * Result of [fileUpdatePatchedSrcWithCascade] / [fileDeletionPatchedSrcWithCascade]:
     * per-version Target outFiles that were actually written (for VFS refresh) plus per-rel
     * failures. Successful versions have correct PatchedSrc + origin entries on disk; failed
     * versions are left in their prior state; downstream versions that match a failed-upstream
     * rel are SKIPPED (see [MergeFailure.Phase.SKIPPED_UPSTREAM_FAILED]).
     */
    data class CascadeMergeResult(
        val writtenFiles: List<File>,
        val failures: List<MergeFailure>,
    )

    /**
     * File-level cascade for a single edited file. Operates on **one** edited rel and its
     * Target chain across the editing version + every downstream version in [perVersionContexts].
     *
     * Per-version dispatch: each version consults its own routing to decide single-file vs
     * sibling-group merge. The cascade resolves Target via that version's routing, so Extensions
     * with cross-name targeting work end-to-end. Edited content is substituted wherever the
     * engine would read [editedRel] from a directory equal to [editedSourceDir] (the editing
     * module's TrueSrc dir). This handles the "base version mid-save, downstream's base IS the
     * editing TrueSrc dir, disk is stale" case the IDE used to handle inline.
     *
     * Per-rel containment: if any version's merge fails for the cascade's Target rel, every
     * downstream version's merge of the same rel is **skipped** with a
     * [MergeFailure.Phase.SKIPPED_UPSTREAM_FAILED] entry whose `cause` is the upstream failure.
     * This avoids "false success" against stale upstream PatchedSrc. The check is isolated to
     * [shouldSkipDueToUpstream] so swapping the in-memory containment map for a persistent
     * `OriginFlag.FAILED` lookup later is a one-helper change.
     *
     * Engine-owned: every per-version step writes via [siblingGroupUpdatePatchedSrc] /
     * [mergeFileContentToFile] which write `_originMap.tsv` and `.routing` sidecars atomically.
     * The IDE's [com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache] picks
     * up the modtime bumps on next read; no explicit invalidation needed.
     */
    @JvmStatic
    fun fileUpdatePatchedSrcWithCascade(
        perVersionContexts: List<VersionContext>,
        editedSourceDir: File,
        editedRel: String,
        editedContent: String,
    ): CascadeMergeResult {
        val writtenFiles = mutableListOf<File>()
        val failures = mutableListOf<MergeFailure>()
        val cascadeFailedRels = mutableMapOf<String, Throwable>()

        var propagatedRel = editedRel

        for (ctx in perVersionContexts) {
            if (ctx.isBase) {
                val skip = shouldSkipDueToUpstream(propagatedRel, ctx.versionIdx, cascadeFailedRels)
                if (skip != null) {
                    failures += skip
                    continue
                }
                try {
                    val result = baseVersionFileUpdatePatchedSrc(
                        trueSrcDir = ctx.currentSrcDir,
                        editedRel = propagatedRel,
                        editedContent = editedContent,
                        versionIdx = ctx.versionIdx,
                        originMapFile = ctx.originMapFile,
                        tolerateParseErrors = true,
                    )
                    writtenFiles += result.writtenFiles
                    failures += result.failures
                    result.failures.firstOrNull { it.rel == propagatedRel }?.let {
                        cascadeFailedRels[propagatedRel] = it.cause
                    }
                } catch (e: Throwable) {
                    failures += MergeFailure(propagatedRel, ctx.versionIdx, MergeFailure.Phase.PLAIN_FILE, e)
                    cascadeFailedRels[propagatedRel] = e
                }
                continue
            }

            val targetRel = ctx.routing.getTarget(propagatedRel) ?: propagatedRel

            val skip = shouldSkipDueToUpstream(targetRel, ctx.versionIdx, cascadeFailedRels)
            if (skip != null) {
                failures += skip
                propagatedRel = targetRel
                continue
            }

            val modifierRels = ctx.routing.getModifiers(targetRel)
            val needsGroupMerge = modifierRels.isNotEmpty() &&
                !(modifierRels.size == 1 && modifierRels[0] == targetRel)

            val baseOriginMap = loadBaseOriginMapFresh(ctx.baseOriginMapFile)

            try {
                if (needsGroupMerge) {
                    val siblingContents = buildMap<String, String> {
                        for (rel in modifierRels) {
                            val c = readWithSubstitution(
                                File(ctx.currentSrcDir, rel), ctx.currentSrcDir,
                                editedSourceDir, editedRel, editedContent, rel,
                            )
                            if (c != null) put(rel, c)
                        }
                    }
                    if (siblingContents.isEmpty()) {
                        propagatedRel = targetRel
                        continue
                    }
                    val baseContent = readWithSubstitution(
                        File(ctx.baseDir, targetRel), ctx.baseDir,
                        editedSourceDir, editedRel, editedContent, targetRel,
                    )
                    siblingGroupUpdatePatchedSrc(
                        targetRel, siblingContents, baseContent,
                        ctx.patchedOutDir, ctx.originMapFile,
                        ctx.versionIdx, ctx.baseVersionIdx,
                        baseOriginMap,
                    )
                    writtenFiles += File(ctx.patchedOutDir, targetRel)
                } else {
                    val fileContent = readWithSubstitution(
                        File(ctx.currentSrcDir, targetRel), ctx.currentSrcDir,
                        editedSourceDir, editedRel, editedContent, targetRel,
                    )
                    val baseContent = readWithSubstitution(
                        File(ctx.baseDir, targetRel), ctx.baseDir,
                        editedSourceDir, editedRel, editedContent, targetRel,
                    )
                    val outFile = File(ctx.patchedOutDir, targetRel)
                    val result = mergeFileContentToFile(
                        fileContent, baseContent, outFile, targetRel,
                        ctx.versionIdx, ctx.baseVersionIdx,
                        baseOriginMap,
                    )
                    val map = OriginMap.fromFile(ctx.originMapFile)
                    map.patchFile(targetRel, result.originEntries)
                    map.toFileAtomic(ctx.originMapFile)
                    writtenFiles += outFile
                }
            } catch (e: Throwable) {
                val phase = if (needsGroupMerge) MergeFailure.Phase.VIRTUAL_TARGET
                            else MergeFailure.Phase.PLAIN_FILE
                failures += MergeFailure(targetRel, ctx.versionIdx, phase, e)
                cascadeFailedRels[targetRel] = e
            }

            propagatedRel = targetRel
        }

        return CascadeMergeResult(writtenFiles.toList(), failures.toList())
    }

    /**
     * Deletion variant of [fileUpdatePatchedSrcWithCascade]. The deleted file is no longer in
     * the editing module's TrueSrc; the cascade re-merges the affected sibling group (or single
     * file) in every version.
     *
     * - If a sibling group at the resolved Target survives with multiple Siblings or a single
     *   Extension after dropping [deletedRel], re-merge via [siblingGroupUpdatePatchedSrc].
     * - Otherwise the single-file path: [fileUpdatePatchedSrc] (engine copies base verbatim or
     *   deletes if base is also absent).
     *
     * Same per-rel containment as [fileUpdatePatchedSrcWithCascade].
     */
    @JvmStatic
    fun fileDeletionPatchedSrcWithCascade(
        perVersionContexts: List<VersionContext>,
        deletedRel: String,
    ): CascadeMergeResult {
        val writtenFiles = mutableListOf<File>()
        val failures = mutableListOf<MergeFailure>()
        val cascadeFailedRels = mutableMapOf<String, Throwable>()

        var propagatedRel = deletedRel

        for (ctx in perVersionContexts) {
            if (ctx.isBase) {
                val skip = shouldSkipDueToUpstream(propagatedRel, ctx.versionIdx, cascadeFailedRels)
                if (skip != null) {
                    failures += skip
                    continue
                }
                try {
                    val result = baseVersionFileUpdatePatchedSrc(
                        trueSrcDir = ctx.currentSrcDir,
                        editedRel = propagatedRel,
                        editedContent = null,
                        versionIdx = ctx.versionIdx,
                        originMapFile = ctx.originMapFile,
                        tolerateParseErrors = true,
                    )
                    writtenFiles += result.writtenFiles
                    failures += result.failures
                    result.failures.firstOrNull { it.rel == propagatedRel }?.let {
                        cascadeFailedRels[propagatedRel] = it.cause
                    }
                } catch (e: Throwable) {
                    failures += MergeFailure(propagatedRel, ctx.versionIdx, MergeFailure.Phase.PLAIN_FILE, e)
                    cascadeFailedRels[propagatedRel] = e
                }
                continue
            }

            val targetRel = ctx.routing.getTarget(propagatedRel) ?: propagatedRel

            val skip = shouldSkipDueToUpstream(targetRel, ctx.versionIdx, cascadeFailedRels)
            if (skip != null) {
                failures += skip
                propagatedRel = targetRel
                continue
            }

            val remainingModifiers = ctx.routing.getModifiers(targetRel).filter { it != deletedRel }
            val outFile = File(ctx.patchedOutDir, targetRel)
            val baseOriginMap = loadBaseOriginMapFresh(ctx.baseOriginMapFile)
            val groupSurvives = remainingModifiers.size >= 2 ||
                (remainingModifiers.size == 1 && remainingModifiers[0] != targetRel)

            try {
                if (groupSurvives) {
                    val baseFile = File(ctx.baseDir, targetRel)
                    val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null
                    val siblingContents = buildMap<String, String> {
                        for (rel in remainingModifiers) {
                            val f = File(ctx.currentSrcDir, rel)
                            if (f.exists()) put(rel, f.readText(Charsets.UTF_8))
                        }
                    }
                    if (siblingContents.isNotEmpty()) {
                        siblingGroupUpdatePatchedSrc(
                            targetRel, siblingContents, baseContent,
                            ctx.patchedOutDir, ctx.originMapFile,
                            ctx.versionIdx, ctx.baseVersionIdx,
                            baseOriginMap,
                        )
                        writtenFiles += outFile
                        propagatedRel = targetRel
                        continue
                    }
                    // Else fall through to single-file path (handles "base exists, overlay absent").
                }

                fileUpdatePatchedSrc(
                    File(ctx.currentSrcDir, targetRel), File(ctx.baseDir, targetRel),
                    outFile, targetRel,
                    ctx.versionIdx, ctx.baseVersionIdx,
                    ctx.originMapFile, baseOriginMap,
                )
                writtenFiles += outFile
            } catch (e: Throwable) {
                val phase = if (groupSurvives) MergeFailure.Phase.VIRTUAL_TARGET
                            else MergeFailure.Phase.PLAIN_FILE
                failures += MergeFailure(targetRel, ctx.versionIdx, phase, e)
                cascadeFailedRels[targetRel] = e
            }

            propagatedRel = targetRel
        }

        return CascadeMergeResult(writtenFiles.toList(), failures.toList())
    }

    /**
     * Cascade containment check. Today this consults the in-memory [cascadeFailedRels] map;
     * the future swap to a persistent `OriginFlag.FAILED` lookup is a single-line change
     * inside this helper and does not touch the cascade loop body.
     */
    private fun shouldSkipDueToUpstream(
        targetRel: String,
        versionIdx: Int,
        cascadeFailedRels: Map<String, Throwable>,
    ): MergeFailure? {
        val cause = cascadeFailedRels[targetRel] ?: return null
        return MergeFailure(targetRel, versionIdx, MergeFailure.Phase.SKIPPED_UPSTREAM_FAILED, cause)
    }

    /**
     * Returns [editedContent] when `(targetDir, rel) == (editedSourceDir, editedRel)`, else
     * reads [targetFile] from disk (returning null if absent). The substitution model handles
     * the "first downstream version after a base-version edit, where the base IS the editing
     * TrueSrc dir, disk stale mid-save" case — without the IDE having to special-case anything.
     */
    private fun readWithSubstitution(
        targetFile: File,
        targetDir: File,
        editedSourceDir: File,
        editedRel: String,
        editedContent: String,
        rel: String,
    ): String? {
        if (rel == editedRel && targetDir.canonicalPath == editedSourceDir.canonicalPath) {
            return editedContent
        }
        return if (targetFile.exists()) targetFile.readText(Charsets.UTF_8) else null
    }

    /**
     * Loads the previous version's origin map from disk, fresh on each cascade step so the
     * prior step's atomic write is picked up. Null when the cascade is starting at the base
     * version (no prior origin map exists).
     */
    private fun loadBaseOriginMapFresh(baseOriginMapFile: File?): OriginMap? {
        if (baseOriginMapFile == null || !baseOriginMapFile.exists()) return null
        return OriginMap.fromFile(baseOriginMapFile)
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
