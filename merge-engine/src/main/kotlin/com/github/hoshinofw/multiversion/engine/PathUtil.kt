package com.github.hoshinofw.multiversion.engine

import java.io.File
import java.nio.file.Path

object PathUtil {
    const val PATCHED_SRC_DIR = "build/patchedSrc"
    const val JAVA_SRC_SUBDIR = "main/java"
    const val RESOURCES_SRC_SUBDIR = "main/resources"
    const val ORIGIN_MAP_FILENAME = "_originMap.tsv"
    const val TRUE_SRC_MARKER = "src/main/java"

    /** Relativizes [file] against [root] and normalizes separators to forward slashes. */
    @JvmStatic
    fun relativize(root: File, file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/')

    /** Relativizes [file] against [root] and normalizes separators to forward slashes. */
    @JvmStatic
    fun relativize(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')
}
