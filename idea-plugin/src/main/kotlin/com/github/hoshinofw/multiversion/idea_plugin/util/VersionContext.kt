package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.VersionUtil
import java.io.File

internal val VERSION_REGEX = Regex("/(${VersionUtil.VERSION_PATTERN.pattern})/")

/**
 * Parses a file path to extract the current version string and the sorted list of
 * version directories in the project. Returns null if the file is not in a versioned module.
 */
internal fun resolveVersionContext(filePath: String): VersionContext? {
    val normPath = filePath.replace('\\', '/')
    val match = VERSION_REGEX.find(normPath) ?: return null
    val currentVersion = match.groupValues[1]

    val versionRoot = normPath.substringBefore("/${currentVersion}/")
    val projectBase = File(versionRoot)
    val versionDirs = projectBase.listFiles { f ->
        f.isDirectory && VersionUtil.looksLikeVersion(f.name)
    }?.sortedWith { a, b -> VersionUtil.compareVersions(a.name, b.name) } ?: return null

    val currentIdx = versionDirs.indexOfFirst { it.name == currentVersion }
    if (currentIdx < 0) return null

    return VersionContext(normPath, currentVersion, currentIdx, versionDirs)
}

data class VersionContext(
    val normPath: String,
    val currentVersion: String,
    val currentIdx: Int,
    val versionDirs: List<File>,
)

/**
 * Extracts the module name and relative class path from a versioned file path.
 * Returns null if the path doesn't contain a trueSrc marker.
 */
internal fun parseVersionedFileInfo(filePath: String, ctx: VersionContext): VersionedFileInfo? {
    val trueSrcMarker = "/${PathUtil.TRUE_SRC_MARKER}/"
    val srcMainJavaIdx = ctx.normPath.indexOf(trueSrcMarker)
    if (srcMainJavaIdx < 0) return null
    val versionSuffix = "/${ctx.currentVersion}/"
    val afterVersion = ctx.normPath.substring(ctx.normPath.indexOf(versionSuffix) + versionSuffix.length)
    return VersionedFileInfo(
        moduleName = afterVersion.substringBefore(trueSrcMarker),
        relClassPath = ctx.normPath.substring(srcMainJavaIdx + trueSrcMarker.length),
    )
}

internal data class VersionedFileInfo(
    val moduleName: String,
    val relClassPath: String,
)