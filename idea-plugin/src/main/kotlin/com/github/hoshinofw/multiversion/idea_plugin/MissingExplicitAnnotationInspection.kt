package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.JavaTokenType
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

class MissingExplicitAnnotationInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return
    val containingClass = member.containingClass ?: return
    val prevClass = findPreviousVersionClass(containingClass) ?: return

    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    val hasShadow = modList.hasAnnotation(SHADOW_FQN)
    val hasModifySignature = modList.hasAnnotation(MODIFY_SIGNATURE_FQN)

    if (hasModifySignature) {
        checkModifySignatureCollision(member, modList, prevClass, holder)
        if (!hasOverwrite && member is PsiMethod && member.body != null) {
            val nameElement = member.nameIdentifier ?: return
            holder.registerProblem(
                nameElement,
                "@ModifySignature member has a body but no @OverwriteVersion",
                AddExplicitAnnotationFix("OverwriteVersion", OVERWRITE_FQN)
            )
        }
        return
    }

    if (hasOverwrite || hasShadow) {
        val hasBody = when (member) {
            is PsiMethod -> member.body != null
            is PsiField -> member.initializer != null
            else -> false
        }
        if (hasShadow && hasBody) {
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
                RemoveMemberBodyFix()
            )
        }
        return
    }

    val match = findMatchingMember(member, prevClass) ?: return

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    val hasBody = when (member) {
        is PsiMethod -> member.body != null
        is PsiField -> member.initializer != null
        else -> false
    }
    val annotationToAdd = if (hasBody) "OverwriteVersion" else "ShadowVersion"
    val annotationFqn = if (hasBody) OVERWRITE_FQN else SHADOW_FQN

    holder.registerProblem(
        nameElement,
        "Member found in original class, but no explicit annotation given",
        AddExplicitAnnotationFix(annotationToAdd, annotationFqn)
    )
}

private fun checkModifySignatureCollision(
    member: PsiMember,
    modList: PsiModifierList,
    prevClass: PsiClass,
    holder: ProblemsHolder
) {
    val annotation = modList.findAnnotation(MODIFY_SIGNATURE_FQN) ?: return
    val descriptorValue = annotation.findAttributeValue("value") ?: return
    val descriptor = (descriptorValue as? PsiLiteralExpression)?.value as? String ?: return

    // The descriptor points to the OLD member in the previous version.
    // The member's current name+params is its NEW identity.
    // Check if the new identity collides with a different member in the previous version.
    val newIdentityMatch = findMatchingMember(member, prevClass) ?: return

    // Resolve the descriptor target in the previous version
    val descriptorTarget = resolveDescriptorInClass(descriptor, prevClass)

    // If the new identity matches the same member as the descriptor target, no collision
    if (descriptorTarget != null && descriptorTarget.isEquivalentTo(newIdentityMatch)) return

    val memberName = when (member) {
        is PsiMethod -> if (member.isConstructor) "<init>" else member.name
        is PsiField -> member.name
        else -> return
    }

    val nameElement = when (member) {
        is PsiMethod -> member.nameIdentifier ?: return
        is PsiField -> member.nameIdentifier
        else -> return
    }

    holder.registerProblem(
        nameElement,
        "@ModifySignature collision: '$memberName' already exists in the original class"
    )
}

private class AddExplicitAnnotationFix(
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
