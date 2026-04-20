package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.OVERWRITE_FQN
import com.github.hoshinofw.multiversion.idea_plugin.util.SHADOW_FQN
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

class ShadowVersionBodyInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return
    if (!modList.hasAnnotation(SHADOW_FQN)) return

    val hasBody = when (member) {
        is PsiMethod -> member.body != null
        is PsiField -> member.initializer != null
        else -> false
    }
    if (!hasBody) return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    holder.registerProblem(
        nameElement,
        "@ShadowVersion member has a body that will be ignored",
        ProblemHighlightType.WEAK_WARNING,
        ReplaceAnnotationFix("ShadowVersion", SHADOW_FQN, "OverwriteVersion", OVERWRITE_FQN),
        RemoveMemberBodyFix(),
    )
}

private class ReplaceAnnotationFix(
    private val oldName: String,
    private val oldFqn: String,
    private val newName: String,
    private val newFqn: String,
) : LocalQuickFix {

    override fun getName(): String = "Replace @$oldName with @$newName"

    override fun getFamilyName(): String = "Replace multiversion annotation"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiModifierListOwner::class.java) ?: return
        val modList = member.modifierList ?: return
        val oldAnnotation = modList.findAnnotation(oldFqn) ?: return

        val factory = JavaPsiFacade.getElementFactory(project)
        // FQN so shortenClassReferences adds the new import if needed.
        val newAnnotation = factory.createAnnotationFromText("@$newFqn", member)
        oldAnnotation.replace(newAnnotation)

        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }
}

private class RemoveMemberBodyFix : LocalQuickFix {

    override fun getName(): String = "Remove member body"

    override fun getFamilyName(): String = "Remove member body"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val member = PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiMember::class.java) ?: return
        when (member) {
            is PsiMethod -> {
                val body = member.body ?: return
                body.delete()
                member.node.addLeaf(JavaTokenType.SEMICOLON, ";", null)
            }
            is PsiField -> {
                val initializer = member.initializer ?: return
                // Remove the `= initializer` portion: delete from the equals sign to the initializer
                val equalsSign = member.children.firstOrNull { it.text == "=" }
                if (equalsSign != null) {
                    member.deleteChildRange(equalsSign, initializer)
                } else {
                    initializer.delete()
                }
            }
        }
    }
}
