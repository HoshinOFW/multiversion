package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

class OverwriteDuplicateClassHighlightFilter : HighlightInfoFilter {

    companion object {
        private const val PATCHED_SEGMENT = "/build/patchedSrc/"
    }

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        val javaFile = file as? PsiJavaFile ?: return true
        if (info.severity != HighlightSeverity.ERROR) return true

        val description = info.description ?: return true
        if (!description.contains("Duplicate class", ignoreCase = true)) return true

        val project = javaFile.project
        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        for (psiClass in javaFile.classes) {
            val fqn = psiClass.qualifiedName ?: continue

            val candidates = facade.findClasses(fqn, scope)
            if (candidates.size < 2) continue

            val hasPatched = candidates.any { it.containingFile?.virtualFile.isPatched() }
            if (!hasPatched) continue

            val hasNonPatched = candidates.any { !(it.containingFile?.virtualFile.isPatched()) }
            if (!hasNonPatched) continue

            return false
        }

        return true
    }

    private fun VirtualFile?.isPatched(): Boolean {
        val p = this?.path ?: return false
        val norm = p.replace('\\', '/') //Normalize between OS
        return norm.contains(PATCHED_SEGMENT)
    }
}
