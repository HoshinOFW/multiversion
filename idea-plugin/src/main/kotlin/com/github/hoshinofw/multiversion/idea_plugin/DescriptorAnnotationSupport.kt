package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.VersionUtil
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import java.io.File

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
    val name = descriptor.substringBefore("(")
    val hasParams = descriptor.contains("(")

    if (name == "init") {
        val ctors = targetClass.constructors
        val methods = targetClass.findMethodsByName("init", false)
        val field = targetClass.findFieldByName("init", false)

        // "init" without params
        if (!hasParams) {
            // Field named "init" is always illegal in descriptors due to constructor ambiguity
            if (field != null)
                return "Cannot reference field 'init': ambiguous with constructor"

            val candidates = ctors.size + methods.size
            if (candidates == 0)
                return "'init' member reference could not be resolved in ${sourceClass.name}"
            if (candidates == 1) return null // unambiguous
            // Multiple candidates: could be multiple ctors, multiple methods, or mix
            if (ctors.isNotEmpty() && methods.isNotEmpty())
                return "Ambiguous reference: 'init' could reference the constructor or the method. Provide full descriptor with parameter types"
            return "'init' is ambiguous: $candidates overloads exist, specify parameter types"
        }

        // "init(...)" with params
        val paramStr = descriptor.substringAfter("(").substringBeforeLast(")")
        val expectedParams = if (paramStr.isBlank()) emptyList()
        else paramStr.split(",").map { MemberDescriptor.simpleTypeName(it.trim()) }

        val ctorMatch = ctors.find { matchesParams(it, expectedParams) }
        val methodMatch = methods.find { matchesParams(it, expectedParams) }

        if (ctorMatch != null && methodMatch != null)
            return "Ambiguous reference: constructor and method 'init' have the same signature. Cannot resolve"
        if (ctorMatch != null || methodMatch != null) return null
        return "'$descriptor' member reference could not be resolved in ${sourceClass.name}"
    }

    // Non-"init" descriptors
    if (!hasParams) {
        val methods = targetClass.findMethodsByName(name, false)
        if (methods.size == 1) return null
        if (methods.isEmpty()) {
            val field = targetClass.findFieldByName(name, false)
            return if (field != null) null
            else "'$name' member reference could not be resolved in ${sourceClass.name}"
        }
        return "'$name' is ambiguous: ${methods.size} overloads exist, specify parameter types"
    }

    val resolved = resolveDescriptorInClass(descriptor, targetClass)
    return if (resolved != null) null
    else "'$descriptor' member reference could not be resolved in ${sourceClass.name}"
}

private fun matchesParams(method: PsiMethod, expectedParams: List<String>): Boolean {
    val params = method.parameterList.parameters
    if (params.size != expectedParams.size) return false
    return params.zip(expectedParams).all { (p, expected) ->
        MemberDescriptor.simpleTypeName(p.type.presentableText) == expected
    }
}

// -- Shared helpers --

private val VERSION_REGEX = Regex("/(${VersionUtil.VERSION_PATTERN.pattern})/")

/** Returns true if the annotation is one that contains member descriptor strings. */
internal fun isDescriptorAnnotation(qualifiedName: String): Boolean {
    val simple = qualifiedName.substringAfterLast('.')
    return simple.startsWith("Delete") || simple == "ModifySignature"
}

fun findPreviousVersionClass(psiClass: PsiClass): PsiClass? {
    val file = psiClass.containingFile?.virtualFile ?: return null
    val normPath = file.path.replace('\\', '/')
    val match = VERSION_REGEX.find(normPath) ?: return null
    val currentVersion = match.groupValues[1]

    val versionRoot = normPath.substringBefore("/${currentVersion}/")
    val projectBase = File(versionRoot)
    val versionDirs = projectBase.listFiles { f ->
        f.isDirectory && VersionUtil.looksLikeVersion(f.name)
    }?.sortedWith { a, b -> VersionUtil.compareVersions(a.name, b.name) } ?: return null

    val currentIdx = versionDirs.indexOfFirst { it.name == currentVersion }
    if (currentIdx <= 0) return null
    val prevVersion = versionDirs[currentIdx - 1]

    val versionSuffix = "/${currentVersion}/"
    val afterVersion  = normPath.substring(normPath.indexOf(versionSuffix) + versionSuffix.length)
    val trueSrcMarker = "/${PathUtil.TRUE_SRC_MARKER}/"
    val moduleName    = afterVersion.substringBefore(trueSrcMarker)

    val srcMainJavaIdx = normPath.indexOf(trueSrcMarker)
    if (srcMainJavaIdx < 0) return null
    val relClassPath = normPath.substring(srcMainJavaIdx + trueSrcMarker.length)

    // Descriptors reference members in the previous version's patchedSrc (merged output),
    // which contains all inherited members from earlier versions. Fall back to src/main/java
    // for the oldest version (no patchedSrc) or when patchedSrc hasn't been generated yet.
    val patchedFile = File(prevVersion, "$moduleName/${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}/$relClassPath")
    val srcFile = File(prevVersion, "$moduleName/${PathUtil.TRUE_SRC_MARKER}/$relClassPath")
    val prevIoFile = if (patchedFile.exists()) patchedFile else srcFile
    val prevVf = LocalFileSystem.getInstance().findFileByIoFile(prevIoFile) ?: return null
    val prevPsiFile = PsiManager.getInstance(psiClass.project).findFile(prevVf) ?: return null
    return PsiTreeUtil.findChildOfType(prevPsiFile, PsiClass::class.java)
}

fun resolveDescriptorInClass(descriptor: String, cls: PsiClass): PsiElement? {
    val name = descriptor.substringBefore("(")
    val hasParams = descriptor.contains("(")

    // "init" can refer to constructors OR a method literally named "init".
    // Try constructors first; if no match, fall through to the normal method/field lookup.
    if (name == "init") {
        val ctors = cls.constructors
        if (!hasParams && ctors.size == 1) return ctors[0]
        if (hasParams) {
            val paramStr = descriptor.substringAfter("(").substringBeforeLast(")")
            val expectedParams = if (paramStr.isBlank()) emptyList()
            else paramStr.split(",").map { MemberDescriptor.simpleTypeName(it.trim()) }
            val match = ctors.find { ctor ->
                val params = ctor.parameterList.parameters
                params.size == expectedParams.size && params.zip(expectedParams).all { (p, expected) ->
                    MemberDescriptor.simpleTypeName(p.type.presentableText) == expected
                }
            }
            if (match != null) return match
        }
        // Fall through: "init" might be a regular method name
    }

    if (!hasParams) {
        val methods = cls.findMethodsByName(name, false)
        if (methods.size == 1) return methods[0]
        if (methods.isEmpty()) return cls.findFieldByName(name, false)
        return null // ambiguous
    }

    val paramStr = descriptor.substringAfter("(").substringBeforeLast(")")
    val expectedParams = if (paramStr.isBlank()) emptyList()
    else paramStr.split(",").map { MemberDescriptor.simpleTypeName(it.trim()) }

    return cls.findMethodsByName(name, false).find { method ->
        val params = method.parameterList.parameters
        params.size == expectedParams.size && params.zip(expectedParams).all { (p, expected) ->
            MemberDescriptor.simpleTypeName(p.type.presentableText) == expected
        }
    }
}

private fun PsiAnnotation.owner(): PsiClass? {
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

