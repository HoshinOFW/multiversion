package com.github.hoshinofw.multiversion.idea_plugin.util

import com.github.hoshinofw.multiversion.engine.InitTarget
import com.github.hoshinofw.multiversion.engine.MemberDescriptor
import com.intellij.psi.*

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

/**
 * Checks whether [method]'s parameter types match [expectedParams] from a descriptor string,
 * using [MemberDescriptor.matchesParams] for normalization.
 */
fun descriptorParamsMatch(method: PsiMethod, expectedParams: List<String>): Boolean =
    MemberDescriptor.matchesParams(method.parameterList.parameters.map { it.type.presentableText }, expectedParams)

