package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil


internal class AddExplicitAnnotationFix(
    private val annotationName: String,
    private val annotationFqn: String,
) : LocalQuickFix {

    override fun getName(): String = "Add @$annotationName"

    override fun getFamilyName(): String = "Add explicit multiversion annotation"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiModifierListOwner::class.java) ?: return
        val modList = member.modifierList ?: return
        if (modList.hasAnnotation(annotationFqn)) return

        val factory = JavaPsiFacade.getElementFactory(project)
        // Create from FQN so shortenClassReferences adds the missing import
        // before collapsing the reference back to its short form.
        val annotation = factory.createAnnotationFromText("@$annotationFqn", member)
        modList.addBefore(annotation, modList.annotations.firstOrNull() ?: modList.firstChild)

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }
}