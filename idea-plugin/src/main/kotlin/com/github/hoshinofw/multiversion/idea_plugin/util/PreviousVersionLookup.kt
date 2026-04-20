package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.OriginNavigation
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

/**
 * Opens a Java file and returns the first top-level PsiClass, or null.
 */
internal fun openPsiClass(file: File, project: com.intellij.openapi.project.Project): PsiClass? {
    val vf = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return null
    return PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
}

/**
 * Finds the class from the previous version that corresponds to [psiClass].
 * Only returns trueSrc classes, skipping versions where the class is only inherited.
 *
 * Used by inspections that need the immediate upstream class. Navigation features
 * should consult [OriginNavigation] via [buildNavigationContext] instead.
 */
fun findPreviousVersionClass(psiClass: PsiClass): PsiClass? {
    val file = psiClass.containingFile?.virtualFile ?: return null
    val ctx = resolveVersionContext(file.path) ?: return null
    val info = parseVersionedFileInfo(file.path, ctx) ?: return null

    val maps = MergeEngineCache.allOriginMapsFor(ctx, info.moduleName)
    val hit = OriginNavigation.nearestClass(maps, ctx.currentIdx, info.relClassPath, direction = -1)
        ?: return null
    val srcFile =
        File(ctx.versionDirs[hit.versionIdx], "${info.moduleName}/${PathUtil.TRUE_SRC_MARKER}/${info.relClassPath}")
    return openPsiClass(srcFile, psiClass.project)
}