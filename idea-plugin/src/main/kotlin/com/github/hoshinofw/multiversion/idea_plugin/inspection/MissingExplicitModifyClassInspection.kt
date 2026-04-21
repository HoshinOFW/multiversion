package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.idea_plugin.engine.MergeEngineCache
import com.github.hoshinofw.multiversion.idea_plugin.util.*
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.codeStyle.JavaCodeStyleManager

/**
 * Yellow warning on a top-level class that participates in a multi-file `@ModifyClass`
 * modification set (i.e. some sibling file has `@ModifyClass(ThisClass.class)` in the same
 * version) but itself carries no explicit `@ModifyClass` annotation. The class is already
 * treated as an implicit sibling by the merge engine; making it explicit keeps the
 * modification set visible at the source level.
 *
 * Quick fix adds a bare `@ModifyClass` annotation (implicit self-target).
 */
class MissingExplicitModifyClassInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitClass(cls: PsiClass) {
                if (!isMultiversionProject(cls.project)) return
                if (cls.containingClass != null) return  // nested classes have their own inspection
                if (cls.modifierList?.hasAnnotation(MODIFY_CLASS_FQN) == true) return

                val file = cls.containingFile?.virtualFile ?: return
                val moduleRoot = getVersionedModuleRoot(file) ?: return
                val sourceRoot = getVersionedSourceRoot(file) ?: return
                val rel = try {
                    com.github.hoshinofw.multiversion.engine.PathUtil.relativize(sourceRoot.toNioPath(), file.toNioPath())
                } catch (_: Exception) { return }

                val routing = MergeEngineCache.routingForModuleRoot(moduleRoot)
                val modifiers = routing.getModifiers(rel)
                // Fires only for implicit siblings in multi-file groups. A group with a
                // single same-name entry needs no sidecar and no annotation.
                if (modifiers.size <= 1) return
                if (rel !in modifiers) return

                val nameId = cls.nameIdentifier ?: return
                holder.registerProblem(
                    nameId,
                    "Class participates in a multi-file @ModifyClass modification set; add an explicit @ModifyClass",
                    AddBareModifyClassFix(),
                )
            }
        }
}

private class AddBareModifyClassFix : LocalQuickFix {
    override fun getName(): String = "Add @ModifyClass"
    override fun getFamilyName(): String = "Multiversion"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val cls = descriptor.psiElement.parent as? PsiClass ?: return
        val modList = cls.modifierList ?: return
        if (modList.hasAnnotation(MODIFY_CLASS_FQN)) return

        val factory = JavaPsiFacade.getElementFactory(project)
        val annotation = factory.createAnnotationFromText("@$MODIFY_CLASS_FQN", cls)
        modList.addBefore(annotation, modList.annotations.firstOrNull() ?: modList.firstChild)
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(modList)
    }

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}
