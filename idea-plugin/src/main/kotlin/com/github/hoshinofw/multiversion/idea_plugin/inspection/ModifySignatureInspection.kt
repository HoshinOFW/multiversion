package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

class ModifySignatureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitMethod(method: PsiMethod) = checkMember(method, holder)
            override fun visitField(field: PsiField) = checkMember(field, holder)
        }
}

private fun checkMember(member: PsiMember, holder: ProblemsHolder) {
    if (!isMultiversionProject(member.project)) return
    val modList = (member as? PsiModifierListOwner)?.modifierList ?: return
    if (!modList.hasAnnotation(MODIFY_SIGNATURE_FQN)) return

    val containingClass = member.containingClass ?: return
    val prevClass = findPreviousVersionClass(containingClass) ?: return

    checkCollision(member, modList, prevClass, holder)

    val hasOverwrite = modList.hasAnnotation(OVERWRITE_FQN)
    if (!hasOverwrite && member is PsiMethod && member.body != null) {
        val nameElement = member.nameIdentifier ?: return
        holder.registerProblem(
            nameElement,
            "@ModifySignature member has a body but no @OverwriteVersion",
            AddExplicitAnnotationFix("OverwriteVersion", OVERWRITE_FQN),
        )
    }
}

private fun checkCollision(
    member: PsiMember,
    modList: PsiModifierList,
    prevClass: PsiClass,
    holder: ProblemsHolder,
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
        "@ModifySignature collision: '$memberName' already exists in the original class",
    )
}
