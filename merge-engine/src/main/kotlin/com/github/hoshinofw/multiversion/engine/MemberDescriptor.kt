package com.github.hoshinofw.multiversion.engine

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
}
