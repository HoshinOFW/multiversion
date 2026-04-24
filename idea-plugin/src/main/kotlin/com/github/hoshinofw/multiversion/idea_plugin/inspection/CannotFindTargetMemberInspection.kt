package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.*

class CannotFindTargetMemberInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return

    val overwriteAnno = modList.findAnnotation(OVERWRITE_FQN)
    val shadowAnno = modList.findAnnotation(SHADOW_FQN)
    if (overwriteAnno == null && shadowAnno == null) return

    if (MemberLookup.memberExistsUpstream(member)) return

    overwriteAnno?.let {
        holder.registerProblem(
            it,
            "@OverwriteVersion target member not found in any upstream version",
            RemoveAnnotationFix("OverwriteVersion"),
        )
    }
    shadowAnno?.let {
        holder.registerProblem(
            it,
            "@ShadowVersion target member not found in any upstream version",
            RemoveAnnotationFix("ShadowVersion"),
        )
    }
}

internal class RemoveAnnotationFix(private val annotationName: String) : LocalQuickFix {
    override fun getName(): String = "Remove @$annotationName"
    override fun getFamilyName(): String = "Remove multiversion annotation"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        descriptor.psiElement.delete()
    }
}
