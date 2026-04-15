package com.github.hoshinofw.multiversion.idea_plugin

import com.github.hoshinofw.multiversion.engine.InitTarget
import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.github.hoshinofw.multiversion.engine.PathUtil
import com.github.hoshinofw.multiversion.engine.VersionUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

private val VERSION_REGEX = Regex("/(${VersionUtil.VERSION_PATTERN.pattern})/")

// -- Previous version class resolution ----------------------------------------

/**
 * Finds the class from the previous version that corresponds to [psiClass].
 * Looks in patchedSrc first (contains all inherited members), falls back to trueSrc.
 */
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

    val patchedFile = File(prevVersion, "$moduleName/${PathUtil.PATCHED_SRC_DIR}/${PathUtil.JAVA_SRC_SUBDIR}/$relClassPath")
    val srcFile = File(prevVersion, "$moduleName/${PathUtil.TRUE_SRC_MARKER}/$relClassPath")
    val prevIoFile = if (patchedFile.exists()) patchedFile else srcFile
    val prevVf = LocalFileSystem.getInstance().findFileByIoFile(prevIoFile) ?: return null
    val prevPsiFile = PsiManager.getInstance(psiClass.project).findFile(prevVf) ?: return null
    return PsiTreeUtil.findChildOfType(prevPsiFile, PsiClass::class.java)
}

// -- Member matching ----------------------------------------------------------

/**
 * Finds the member in [targetClass] that corresponds to [member] by name and parameter types.
 * Works for methods, constructors, and fields.
 */
fun findMatchingMember(member: PsiMember, targetClass: PsiClass): PsiMember? {
    return when (member) {
        is PsiMethod -> {
            val expectedParams = member.simpleParamTypes()
            if (member.isConstructor) {
                targetClass.constructors.find { simpleParamsMatch(it, expectedParams) }
            } else {
                val candidates = targetClass.findMethodsByName(member.name, false)
                candidates.find { simpleParamsMatch(it, expectedParams) } ?: candidates.singleOrNull()
            }
        }
        is PsiField -> targetClass.findFieldByName(member.name, false)
        else -> null
    }
}

/**
 * Finds the element in [targetClass] that corresponds to [element].
 * Like [findMatchingMember] but also supports PsiClass matching.
 */
fun findMatchingElement(element: PsiElement, targetClass: PsiClass): PsiElement? {
    return when (element) {
        is PsiClass -> if (targetClass.name == element.name) targetClass else null
        is PsiMember -> findMatchingMember(element, targetClass)
        else -> null
    }
}

// -- Descriptor resolution ----------------------------------------------------

/**
 * Resolves a member descriptor string (e.g. "foo(int,String)") to the PSI element in [cls].
 * Handles constructors (via "init"), methods, and fields, including ambiguity.
 */
fun resolveDescriptorInClass(descriptor: String, cls: PsiClass): PsiElement? {
    val parsed = MemberDescriptor.parseDescriptor(descriptor)

    if (parsed.name == "init") {
        val ctors = cls.constructors
        val methods = cls.findMethodsByName("init", false)
        val field = cls.findFieldByName("init", false)

        val ctorMatch = if (parsed.params != null) ctors.find { descriptorParamsMatch(it, parsed.params!!) } else null
        val methodMatch = if (parsed.params != null) methods.find { descriptorParamsMatch(it, parsed.params!!) } else null

        val resolution = MemberDescriptor.resolveInitAmbiguity(
            ctorCount = ctors.size, methodCount = methods.size, fieldExists = field != null,
            hasParams = parsed.params != null, ctorMatched = ctorMatch != null, methodMatched = methodMatch != null,
        )
        if (resolution.error != null) return null
        return when (resolution.target) {
            InitTarget.CONSTRUCTOR -> ctorMatch ?: ctors.firstOrNull()
            InitTarget.METHOD -> methodMatch ?: methods.firstOrNull()
            else -> null
        }
    }

    if (parsed.params == null) {
        val methods = cls.findMethodsByName(parsed.name, false)
        if (methods.size == 1) return methods[0]
        if (methods.isEmpty()) return cls.findFieldByName(parsed.name, false)
        return null // ambiguous
    }

    return cls.findMethodsByName(parsed.name, false).find { descriptorParamsMatch(it, parsed.params!!) }
}

// -- Member key construction --------------------------------------------------

/**
 * Builds an originMap-compatible member key for the method, constructor, or field
 * enclosing [element]. Format: `name(Param1,Param2)` for methods,
 * `<init>(Param1,Param2)` for constructors, `name` for fields.
 */
fun memberKey(element: PsiElement): String? {
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
    if (method != null) {
        val params = method.simpleParamTypes().joinToString(",")
        return if (method.isConstructor) "<init>($params)" else "${method.name}($params)"
    }
    val field = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
    if (field != null) return field.name
    return null
}

/**
 * Finds a member in [file] by its originMap key string.
 */
fun findMemberByKey(file: PsiFile, memberKey: String): PsiElement? {
    val javaFile = file as? PsiJavaFile ?: return null
    val cls = javaFile.classes.firstOrNull() ?: return null

    if (memberKey.startsWith("<init>(")) {
        val params = parseKeyParams(memberKey)
        return cls.constructors.find { simpleParamsMatch(it, params) }
    }

    val parenIdx = memberKey.indexOf('(')
    if (parenIdx >= 0) {
        val name = memberKey.substring(0, parenIdx)
        val params = parseKeyParams(memberKey)
        return cls.methods.find { it.name == name && simpleParamsMatch(it, params) }
    }

    return cls.fields.find { it.name == memberKey }
}

// -- Parameter matching -------------------------------------------------------

/**
 * Checks whether [method]'s parameter types match [expectedParams] from a descriptor string,
 * using [MemberDescriptor.matchesParams] for normalization.
 */
fun descriptorParamsMatch(method: PsiMethod, expectedParams: List<String>): Boolean =
    MemberDescriptor.matchesParams(method.parameterList.parameters.map { it.type.presentableText }, expectedParams)

/**
 * Checks whether [method]'s parameter types match [expected] simple type names
 * (as produced by [MemberDescriptor.simpleTypeName]).
 */
fun simpleParamsMatch(method: PsiMethod, expected: List<String>): Boolean {
    val params = method.parameterList.parameters
    if (params.size != expected.size) return false
    return params.indices.all { i ->
        MemberDescriptor.simpleTypeName(params[i].type.presentableText) == expected[i]
    }
}

// -- Internal helpers ---------------------------------------------------------

private fun PsiMethod.simpleParamTypes(): List<String> =
    parameterList.parameters.map { MemberDescriptor.simpleTypeName(it.type.presentableText) }

private fun parseKeyParams(key: String): List<String> {
    val inner = key.substringAfter("(").substringBefore(")")
    return if (inner.isEmpty()) emptyList() else inner.split(",")
}
