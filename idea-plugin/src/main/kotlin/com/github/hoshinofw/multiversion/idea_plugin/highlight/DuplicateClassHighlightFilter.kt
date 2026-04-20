package com.github.hoshinofw.multiversion.idea_plugin.highlight

import com.github.hoshinofw.multiversion.engine.VersionUtil
import com.github.hoshinofw.multiversion.idea_plugin.util.isInPatchedSrc
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope

private val VERSION_DIR_REGEX = Regex("/(${VersionUtil.VERSION_PATTERN.pattern})/")

class DuplicateClassHighlightFilter : HighlightInfoFilter {

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        val javaFile = file as? PsiJavaFile ?: return true
        if (!isMultiversionProject(javaFile.project)) return true
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

            val paths = candidates.mapNotNull { c ->
                c.containingFile?.virtualFile?.path?.replace('\\', '/')
            }

            val hasPatched = paths.any { p -> isInPatchedSrc(p) }
            val hasNonPatched = paths.any { p -> !isInPatchedSrc(p) }
            if (hasPatched && hasNonPatched) return false

            val versions = paths.mapNotNull { p -> VERSION_DIR_REGEX.find(p)?.groupValues?.get(1) }.toSet()
            if (versions.size >= 2) return false
        }

        return true
    }
}