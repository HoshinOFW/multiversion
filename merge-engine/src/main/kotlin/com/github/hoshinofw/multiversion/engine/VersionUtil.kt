package com.github.hoshinofw.multiversion.engine

object VersionUtil {
    /** Pattern matching a version string like "1.20.1" or "1.21". Two or three numeric parts. */
    @JvmField
    val VERSION_PATTERN: Regex = Regex("""\d+\.\d+(?:\.\d+)?""")

    /** Returns true if [s] looks like a version string (e.g. "1.20.1", "1.21"). */
    @JvmStatic
    fun looksLikeVersion(s: String): Boolean = VERSION_PATTERN.matches(s)

    /** Semantic version comparison ("1.20.1" < "1.21.0"). */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }
}
