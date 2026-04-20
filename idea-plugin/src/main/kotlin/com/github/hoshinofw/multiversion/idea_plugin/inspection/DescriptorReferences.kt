package com.github.hoshinofw.multiversion.idea_plugin.inspection

import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.idea_plugin.util.descriptorParamsMatch
import com.github.hoshinofw.multiversion.idea_plugin.util.findPreviousVersionClass
import com.github.hoshinofw.multiversion.idea_plugin.util.isMultiversionProject
import com.github.hoshinofw.multiversion.idea_plugin.util.resolveDescriptorInClass
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

class DescriptorReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiLiteralExpression::class.java),
            DescriptorReferenceProvider()
        )
    }
}

private class DescriptorReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        if (!isMultiversionProject(literal.project)) return PsiReference.EMPTY_ARRAY
        val value = literal.value as? String ?: return PsiReference.EMPTY_ARRAY
        val annotation = enclosingDescriptorAnnotation(literal) ?: return PsiReference.EMPTY_ARRAY
        val ownerClass = annotation.owner() ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(DescriptorReference(literal, value, ownerClass))
    }
}

class DescriptorReference(
    private val literal: PsiLiteralExpression,
    private val descriptor: String,
    private val annotatedClass: PsiClass
) : PsiReferenceBase<PsiLiteralExpression>(literal, TextRange(1, literal.textLength - 1)) {

    override fun resolve(): PsiElement? {
        val prevClass = findPreviousVersionClass(annotatedClass) ?: return null
        return resolveDescriptorInClass(descriptor, prevClass)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    override fun handleElementRename(newElementName: String): PsiElement {
        val current = literal.value as? String ?: return literal
        // Only skip rename for actual constructors, not methods named "init"
        if (current == "init" || current.startsWith("init(")) {
            val target = resolve()
            if (target is PsiMethod && target.isConstructor) return literal
        }
        val parenIdx = current.indexOf('(')
        val newText = if (parenIdx >= 0) "$newElementName${current.substring(parenIdx)}" else newElementName
        return ElementManipulators.handleContentChange(literal, rangeInElement, newText)
    }
}

// -- Inspection ---

class DescriptorAnnotationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (!isMultiversionProject(annotation.project)) return
                if (!isDescriptorAnnotation(annotation.qualifiedName.orEmpty())) return
                val psiClass = annotation.owner() ?: return
                val prevClass = findPreviousVersionClass(psiClass) ?: return

                forEachDescriptorLiteral(annotation) { literal, descriptor ->
                    val error = validateDescriptor(descriptor, prevClass, psiClass)
                    if (error != null) holder.registerProblem(literal, error)
                }
            }
        }
}

// -- Validation --

/**
 * Returns an error message if the descriptor is invalid, or null if it resolves cleanly.
 * Handles all ambiguity cases for "init" (constructor vs method vs field).
 */
private fun validateDescriptor(descriptor: String, targetClass: PsiClass, sourceClass: PsiClass): String? {
    val parsed = MemberDescriptor.parseDescriptor(descriptor)

    if (parsed.name == "init") {
        val ctors = targetClass.constructors
        val methods = targetClass.findMethodsByName("init", false)
        val field = targetClass.findFieldByName("init", false)

        val ctorMatch = if (parsed.params != null) ctors.find { descriptorParamsMatch(it, parsed.params!!) } else null
        val methodMatch = if (parsed.params != null) methods.find { descriptorParamsMatch(it, parsed.params!!) } else null

        val resolution = MemberDescriptor.resolveInitAmbiguity(
            ctorCount = ctors.size, methodCount = methods.size, fieldExists = field != null,
            hasParams = parsed.params != null, ctorMatched = ctorMatch != null, methodMatched = methodMatch != null,
        )
        if (resolution.error != null) return resolution.error
        return null
    }

    // Non-"init" descriptors
    if (parsed.params == null) {
        val methods = targetClass.findMethodsByName(parsed.name, false)
        if (methods.size == 1) return null
        if (methods.isEmpty()) {
            val field = targetClass.findFieldByName(parsed.name, false)
            return if (field != null) null
            else "'${parsed.name}' member reference could not be resolved in ${sourceClass.name}"
        }
        return "'${parsed.name}' is ambiguous: ${methods.size} overloads exist, specify parameter types"
    }

    val resolved = resolveDescriptorInClass(descriptor, targetClass)
    return if (resolved != null) null
    else "'$descriptor' member reference could not be resolved in ${sourceClass.name}"
}

// -- Shared helpers --

/** Returns true if the annotation is one that contains member descriptor strings. */
internal fun isDescriptorAnnotation(qualifiedName: String): Boolean {
    val simple = qualifiedName.substringAfterLast('.')
    return simple.startsWith("Delete") || simple == "ModifySignature"
}

internal fun PsiAnnotation.owner(): PsiClass? {
    // For class-level annotations (@DeleteMethodsAndFields), the owner is the annotated class.
    // For member-level annotations (@ModifySignature), the owner is the containing class.
    val parent = PsiTreeUtil.getParentOfType(this, PsiMember::class.java) ?: return null
    return if (parent is PsiClass) parent else parent.containingClass
}

private fun enclosingDescriptorAnnotation(element: PsiElement): PsiAnnotation? {
    val annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation::class.java) ?: return null
    return if (isDescriptorAnnotation(annotation.qualifiedName.orEmpty())) annotation else null
}

private fun forEachDescriptorLiteral(annotation: PsiAnnotation, action: (PsiLiteralExpression, String) -> Unit) {
    annotation.parameterList.attributes.forEach { attr ->
        val value = attr.value ?: return@forEach
        when (value) {
            is PsiArrayInitializerMemberValue ->
                value.initializers.filterIsInstance<PsiLiteralExpression>()
                    .forEach { lit -> (lit.value as? String)?.let { action(lit, it) } }
            is PsiLiteralExpression ->
                (value.value as? String)?.let { action(value, it) }
            else -> {}
        }
    }
}
