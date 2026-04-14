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
        currentSrcRelRoot: String,
        baseRelRoot: String,
        baseOriginMap: OriginMap? = null,
    ): MergeResult = True2PatchMergeEngine.mergeContent(
        currentContent, baseContent, rel, currentSrcRelRoot, baseRelRoot, baseOriginMap
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
        currentSrcRelRoot: String,
        baseRelRoot: String,
        baseOriginMap: OriginMap? = null,
    ): MergeResult {
        val result = True2PatchMergeEngine.mergeContent(
            currentContent, baseContent, rel, currentSrcRelRoot, baseRelRoot, baseOriginMap
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
        currentSrcRelRoot: String,
        baseRelRoot: String,
        baseOriginMap: OriginMap? = null,
    ): MergeResult = True2PatchMergeEngine.mergeContent(
        if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null,
        if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null,
        rel, currentSrcRelRoot, baseRelRoot, baseOriginMap
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
        currentSrcRelRoot: String,
        baseRelRoot: String,
        originMapFile: File? = null,
        baseOriginMap: OriginMap? = null,
    ) {
        val currentContent = if (currentFile.exists()) currentFile.readText(Charsets.UTF_8) else null
        val baseContent = if (baseFile.exists()) baseFile.readText(Charsets.UTF_8) else null
        val result = mergeFileContentToFile(
            currentContent, baseContent, outFile, rel, currentSrcRelRoot, baseRelRoot, baseOriginMap
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
     * [currentSrcRelRoot] and [baseRelRoot] are relative root labels written into [originMap]
     * so the IDE can resolve member origins back to their actual source file.
     */
    @JvmStatic
    fun versionUpdatePatchedSrc(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        originMap: OriginMap?,
        baseOriginMap: OriginMap? = null,
    ) = True2PatchMergeEngine.processVersion(
        currentSrcDir, baseDir, patchedOutDir, currentSrcRelRoot, baseRelRoot, originMap, baseOriginMap
    )

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
