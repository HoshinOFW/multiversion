package com.github.hoshinofw.multiversion.engine

/**
 * Parsed representation of a member descriptor string like `"methodName(Type1, Type2)"`.
 *
 * @property name   The member name (or `"init"` for constructors).
 * @property params Normalized parameter type list, or null if no parentheses were present.
 *                  An empty list means explicit empty parens `"foo()"`.
 */
data class ParsedDescriptor(val name: String, val params: List<String>?)

/** Which kind of member an `"init"` descriptor resolved to. */
enum class InitTarget { CONSTRUCTOR, METHOD }

/**
 * Result of disambiguating an `"init"` descriptor against constructor/method/field counts.
 * Exactly one of [target] or [error] is non-null.
 */
data class InitResolution(val target: InitTarget?, val error: String?)

object MemberDescriptor {
    /** Strips array brackets, varargs, and package prefixes from a type name. */
    @JvmStatic
    fun simpleTypeName(type: String): String {
        val base = type.replace(Regex("""\[]"""), "").replace(Regex("""\.{3}$"""), "").trim()
        val dot = base.lastIndexOf(".")
        return if (dot >= 0) base.substring(dot + 1) else base
    }

    /** Formats a method descriptor: `methodName(Type1,Type2)`. */
    @JvmStatic
    fun methodDescriptor(name: String, paramTypes: List<String>): String =
        "$name(${paramTypes.joinToString(",") { simpleTypeName(it) }})"

    /** Formats a constructor descriptor: `<init>(Type1,Type2)`. */
    @JvmStatic
    fun constructorDescriptor(paramTypes: List<String>): String =
        "<init>(${paramTypes.joinToString(",") { simpleTypeName(it) }})"

    /**
     * Parses a descriptor string into its name and optional parameter list.
     *
     * Examples:
     * - `"foo"` -> `ParsedDescriptor("foo", null)`
     * - `"foo()"` -> `ParsedDescriptor("foo", [])`
     * - `"foo(int, String)"` -> `ParsedDescriptor("foo", ["int", "String"])`
     * - `"init(int)"` -> `ParsedDescriptor("init", ["int"])`
     */
    @JvmStatic
    fun parseDescriptor(descriptor: String): ParsedDescriptor {
        val open = descriptor.indexOf("(")
        if (open < 0) return ParsedDescriptor(descriptor.trim(), null)
        val close = descriptor.lastIndexOf(")")
        val name = descriptor.substring(0, open).trim()
        if (close <= open) return ParsedDescriptor(name, emptyList())
        val inner = descriptor.substring(open + 1, close).trim()
        if (inner.isEmpty()) return ParsedDescriptor(name, emptyList())
        return ParsedDescriptor(name, inner.split(",").map { simpleTypeName(it.trim()) })
    }

    /**
     * Disambiguates an `"init"` descriptor against known constructor/method/field counts.
     *
     * This encodes the shared rules for resolving `"init"` when it could refer to
     * a constructor, a method literally named `"init"`, or a field named `"init"`.
     *
     * When [hasParams] is true, the caller has already performed parameter matching
     * and passes in whether a constructor and/or method matched.
     *
     * @param ctorCount     Number of constructors in the target class.
     * @param methodCount   Number of methods named "init" in the target class.
     * @param fieldExists   Whether a field named "init" exists.
     * @param hasParams     Whether the descriptor included parameter types.
     * @param ctorMatched   Whether a constructor matched the given params (only used when hasParams=true).
     * @param methodMatched Whether a method matched the given params (only used when hasParams=true).
     */
    @JvmStatic
    fun resolveInitAmbiguity(
        ctorCount: Int,
        methodCount: Int,
        fieldExists: Boolean,
        hasParams: Boolean,
        ctorMatched: Boolean = false,
        methodMatched: Boolean = false,
    ): InitResolution {
        if (!hasParams) {
            if (fieldExists)
                return InitResolution(null, "Cannot reference field 'init': ambiguous with constructor")
            val candidates = ctorCount + methodCount
            if (candidates == 0)
                return InitResolution(null, "'init' not found")
            if (candidates == 1)
                return if (ctorCount == 1) InitResolution(InitTarget.CONSTRUCTOR, null)
                else InitResolution(InitTarget.METHOD, null)
            if (ctorCount > 0 && methodCount > 0)
                return InitResolution(null, "'init' could reference the constructor or the method; add parameter types to disambiguate")
            return InitResolution(null, "'init' has $candidates overloads; add parameter types to disambiguate")
        }

        // With params
        if (ctorMatched && methodMatched)
            return InitResolution(null, "Constructor and method 'init' have the same signature; cannot resolve")
        if (ctorMatched) return InitResolution(InitTarget.CONSTRUCTOR, null)
        if (methodMatched) return InitResolution(InitTarget.METHOD, null)
        return InitResolution(null, "'init' with given parameters not found")
    }

    /**
     * Checks whether a list of actual parameter type names matches a list of expected
     * (already-simplified) parameter type names.
     *
     * [actualParams] are raw type names from the AST (will be simplified via [simpleTypeName]).
     * [expectedParams] are already simplified (from [parseDescriptor]).
     */
    @JvmStatic
    fun matchesParams(actualParams: List<String>, expectedParams: List<String>): Boolean {
        if (actualParams.size != expectedParams.size) return false
        for (i in actualParams.indices) {
            if (simpleTypeName(actualParams[i]) != expectedParams[i]) return false
        }
        return true
    }
}
