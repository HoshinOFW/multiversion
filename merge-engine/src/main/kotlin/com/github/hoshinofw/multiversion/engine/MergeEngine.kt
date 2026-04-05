package com.github.hoshinofw.multiversion.engine

import java.io.BufferedWriter
import java.io.File

object MergeEngine {

    // ---- true src -> patched src ----

    /**
     * Walks [currentSrcDir] and merges every `.java` file into [patchedOutDir],
     * using [baseDir] as the accumulated base for this version step.
     *
     * [currentSrcRelRoot] and [baseRelRoot] are relative root labels written into [mapOut]
     * so the IDE can resolve member origins back to their actual source file.
     */
    fun versionUpdatePatchedSrc(
        currentSrcDir: File,
        baseDir: File,
        patchedOutDir: File,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) = True2PatchMergeEngine.processVersion(
        currentSrcDir, baseDir, patchedOutDir, currentSrcRelRoot, baseRelRoot, mapOut
    )

    /**
     * Merges a single [currentFile] into [outFile] using [baseFile] as the base for this version step.
     * Intended for on-save IDE integration where only one file changes at a time.
     *
     * [rel] is the file's path relative to its source root (e.g. `com/example/Foo.java`).
     */
    fun fileUpdatePatchedSrc(
        currentFile: File,
        baseFile: File,
        outFile: File,
        rel: String,
        currentSrcRelRoot: String,
        baseRelRoot: String,
        mapOut: BufferedWriter?,
    ) = True2PatchMergeEngine.mergeFile(
        currentFile, baseFile, outFile, rel, currentSrcRelRoot, baseRelRoot, mapOut
    )

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
