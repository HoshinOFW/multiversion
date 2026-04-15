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

