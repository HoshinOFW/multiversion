package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.VersionUtil
import com.github.hoshinofw.multiversion.engine.VersionUtil.compareVersions
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

private val VERSION_PATTERN = VersionUtil.VERSION_PATTERN

private const val SENTINEL_CLASS = "com.github.hoshinofw.multiversion.DeleteMethodsAndFields"

/**
 * Returns true only if the multiversion annotations library is on the classpath of [project].
 * Returns false during dumb mode (indexing not ready) to avoid IndexNotReadyException.
 * Use this to guard all plugin features so they don't interfere with unrelated codebases.
 */
fun isMultiversionProject(project: Project): Boolean {
    if (DumbService.isDumb(project)) return false
    return JavaPsiFacade.getInstance(project)
        .findClass(SENTINEL_CLASS, GlobalSearchScope.allScope(project)) != null
}

/**
 * Returns the `src/main/java` source root if [file] lives inside a versioned module
 * (`<project>/<version>/<module>/src/main/java/...`), or null otherwise.
 */
fun getVersionedSourceRoot(file: VirtualFile): VirtualFile? {
    var cur: VirtualFile? = file.parent
    while (cur != null) {
        if (cur.name == "java" && cur.parent?.name == "main" && cur.parent?.parent?.name == "src") {
            val moduleDir  = cur.parent?.parent?.parent ?: return null  // src → module
            val versionDir = moduleDir.parent           ?: return null  // module → version
            if (VERSION_PATTERN.matches(versionDir.name)) return cur
            return null
        }
        cur = cur.parent
    }
    return null
}

/** Returns the `<version>/<module>` directory for [file], or null if not in a versioned module. */
fun getVersionedModuleRoot(file: VirtualFile): VirtualFile? =
    getVersionedSourceRoot(file)?.parent?.parent?.parent  // java → main → src → module

/**
 * Returns every `<version>/<module>` root in the project regardless of module name,
 * by scanning all version directories under the project root (grandparent of [anyModuleRoot]).
 */
fun findAllVersionModuleRootsAcrossProject(anyModuleRoot: VirtualFile): List<VirtualFile> {
    val versionsRoot = anyModuleRoot.parent?.parent ?: return emptyList()
    return versionsRoot.children
        .filter { it.isDirectory && VERSION_PATTERN.matches(it.name) }
        .flatMap { versionDir -> versionDir.children.filter { it.isDirectory } }
}

/**
 * Returns all `<version>/<module>` roots that share the same module name as [moduleRoot],
 * sorted oldest-to-newest.
 */
fun findAllVersionModuleRoots(moduleRoot: VirtualFile): List<VirtualFile> {
    val moduleName   = moduleRoot.name
    val versionsRoot = moduleRoot.parent?.parent ?: return emptyList()  // version → project root
    return versionsRoot.children
        .filter  { it.isDirectory && VERSION_PATTERN.matches(it.name) }
        .sortedWith { a, b -> compareVersions(a.name, b.name) }
        .mapNotNull { it.findChild(moduleName) }
        .filter  { it.isDirectory }
}

/** Returns all version module roots that come strictly after [moduleRoot] by version. */
fun findLaterVersionModuleRoots(moduleRoot: VirtualFile): List<VirtualFile> {
    val currentVersion = moduleRoot.parent?.name ?: return emptyList()
    return findAllVersionModuleRoots(moduleRoot)
        .filter { compareVersions(it.parent?.name ?: "", currentVersion) > 0 }
}

/**
 * Given a file under [originSrcRoot], returns the corresponding file under
 * `<targetModuleRoot>/src/main/java/`, or null if it doesn't exist.
 */
fun findCorrespondingFile(originFile: VirtualFile, originSrcRoot: VirtualFile, targetModuleRoot: VirtualFile): VirtualFile? =
    try {
        val rel = PathUtil.relativize(originSrcRoot.toNioPath(), originFile.toNioPath())
        targetModuleRoot.findFileByRelativePath("${PathUtil.TRUE_SRC_MARKER}/$rel")
    } catch (_: Exception) { null }

/**
 * Like [findCorrespondingFile] but checks `build/patchedSrc/main/java/` instead of `src/main/java/`.
 * Used when the class only exists as inherited content in patchedSrc (no trueSrc override).
 */
fun findCorrespondingPatchedFile(originFile: VirtualFile, originSrcRoot: VirtualFile, targetModuleRoot: VirtualFile): VirtualFile? =
    try {
        val rel = PathUtil.relativize(originSrcRoot.toNioPath(), originFile.toNioPath())
        targetModuleRoot.findFileByRelativePath("${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}/$rel")
    } catch (_: Exception) { null }

// -- Path classification helpers ------------------------------------------------

private const val PATCHED_MARKER = "/${PathUtil.PATCHED_SRC_DIR}/"
private const val TRUE_SRC_MARKER = "/${PathUtil.TRUE_SRC_MARKER}/"

/** Returns true if [normalizedPath] points inside a patchedSrc directory. */
fun isInPatchedSrc(normalizedPath: String): Boolean =
    normalizedPath.contains(PATCHED_MARKER)

/**
 * Returns the patchedSrc directory root (up to and including `build/patchedSrc`),
 * or null if the path is not inside patchedSrc.
 */
fun patchedSrcRoot(normalizedPath: String): String? {
    val idx = normalizedPath.indexOf(PATCHED_MARKER)
    if (idx < 0) return null
    return normalizedPath.substring(0, idx + PATCHED_MARKER.length - 1) // exclude trailing /
}

/**
 * Returns the relative path inside a patchedSrc directory (after `build/patchedSrc/`),
 * or null if [normalizedPath] is not under [patchedRoot].
 */
fun relInsidePatchedSrc(normalizedPath: String, patchedRoot: String): String? {
    val prefix = patchedRoot.trimEnd('/') + "/"
    return if (normalizedPath.startsWith(prefix)) normalizedPath.removePrefix(prefix) else null
}

/**
 * Extracts the module root path from a patchedSrc root path.
 * E.g. `.../1.21.1/fabric/build/patchedSrc` -> `.../1.21.1/fabric`
 */
fun moduleRootFromPatchedSrc(patchedRoot: String): String =
    patchedRoot.substringBeforeLast("/${PathUtil.PATCHED_SRC_DIR}")

/**
 * Parses a trueSrc file path into its module root and relative class path.
 * Returns null if the path does not contain a trueSrc marker.
 */
fun parseTrueSrcPath(normalizedPath: String): TrueSrcPathInfo? {
    val markerIdx = normalizedPath.indexOf(TRUE_SRC_MARKER)
    if (markerIdx < 0) return null
    return TrueSrcPathInfo(
        moduleRootPath = normalizedPath.substring(0, markerIdx),
        relClassPath = normalizedPath.substring(markerIdx + TRUE_SRC_MARKER.length)
    )
}

data class TrueSrcPathInfo(
    val moduleRootPath: String,
    val relClassPath: String,
)

