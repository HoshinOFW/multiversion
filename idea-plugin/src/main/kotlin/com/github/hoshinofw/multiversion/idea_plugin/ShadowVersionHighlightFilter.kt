package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil


private val VARIABLE_NAME_PATTERN = Regex("""Variable '(\w+)'""")

class ShadowVersionHighlightFilter : HighlightInfoFilter {
    override fun accept(info: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null) return true
        if (info.severity != HighlightSeverity.ERROR && info.severity != HighlightSeverity.WARNING) return true
        if (!isMultiversionProject(file.project)) return true
        val desc = info.description ?: return true

        val element = file.findElementAt(info.startOffset) ?: return true

        if (desc.contains("might not have been initialized")
            || desc.contains("must be initialized")) {
            return !isShadowField(element, desc)
        }

        if (desc.contains("Attribute value must be constant")) {
            return !referencesShadowField(element)
        }

        if (desc.contains("Missing method body") || desc.contains("declare abstract")) {
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
                ?: return true
            if (method.hasAnnotation(SHADOW_FQN)) return false
            // Also suppress when the same member exists in a previous version's class.
            // The "Member found in original class" inspection will fire and offer the
            // correct quick fix (add @ShadowVersion / @OverwriteVersion); showing the
            // generic "add body / declare abstract" fix alongside it is confusing.
            val containingClass = method.containingClass ?: return true
            val prevClass = findPreviousVersionClass(containingClass) ?: return true
            return findMatchingMember(method, prevClass) == null
        }

        return true
    }

    /**
     * Checks whether the "might not have been initialized" error refers to a @ShadowVersion field,
     * or a field that exists in a previous version but hasn't been annotated yet.
     * The error can appear at the field declaration itself, or at a constructor/initializer block
     * where the field should have been assigned.
     */
    private fun isShadowField(element: PsiElement, desc: String): Boolean {
        // Case 1: error is directly inside the field declaration
        val directField = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
        if (directField != null) return isShadowOrPreviousVersionField(directField)

        // Case 2: error is at a constructor or class body (e.g. closing brace).
        // Extract the field name from the message and look it up in the enclosing class.
        val fieldName = VARIABLE_NAME_PATTERN.find(desc)?.groupValues?.get(1) ?: return false
        val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return false
        val field = cls.findFieldByName(fieldName, false) ?: return false
        return isShadowOrPreviousVersionField(field)
    }

    /** @ShadowVersion check first (fast), then previous-version lookup (slower). */
    private fun isShadowOrPreviousVersionField(field: PsiField): Boolean {
        if (field.hasAnnotation(SHADOW_FQN)) return true
        val containingClass = field.containingClass ?: return false
        val prevClass = findPreviousVersionClass(containingClass) ?: return false
        return findMatchingMember(field, prevClass) != null
    }

    /**
     * Checks whether the element at the error position references a @ShadowVersion field.
     * Handles both direct references (MY_FIELD) and qualified references (MyClass.MY_FIELD)
     * inside annotation values.
     */
    private fun referencesShadowField(element: PsiElement): Boolean {
        // Walk up to find the PsiReferenceExpression that resolves to the field
        val refExpr = PsiTreeUtil.getParentOfType(element, PsiReferenceExpression::class.java, false)
        if (refExpr != null) {
            val resolved = refExpr.resolve()
            if (resolved is PsiField) return resolved.hasAnnotation(SHADOW_FQN)
        }

        // Fallback: try the element's own reference or its parent's reference
        val ref = element.reference ?: element.parent?.reference
        val resolved = ref?.resolve()
        if (resolved is PsiField) return resolved.hasAnnotation(SHADOW_FQN)

        return false
    }

}