package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class ShadowVersionHighlightFilter : HighlightInfoFilter {

    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || info.severity != HighlightSeverity.ERROR) return true
        val desc = info.description ?: return true
        if (!desc.contains("might not have been initialized")) return true

        val element = file.findElementAt(info.startOffset) ?: return true
        val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java) ?: return true
        return !field.hasAnnotation("com.github.hoshinofw.multiversion.ShadowVersion")
    }
}
