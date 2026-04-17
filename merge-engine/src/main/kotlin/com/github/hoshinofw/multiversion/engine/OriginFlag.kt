package com.github.hoshinofw.multiversion.engine

/**
 * Flags that can appear in the third tab-separated column of an origin-map TSV entry.
 *
 * The tokens below are the stable wire format; the enum values are the stable in-code
 * reference. The merge engine writes these; the IDE reads them. IDE code must reference
 * [OriginFlag] identifiers rather than duplicate token strings so that the set of
 * recognised flags stays centralised here.
 *
 * Flag semantics per entry:
 * - File-level entries (`com/Foo.java`): only [TRUESRC] is meaningful, indicating the
 *   current version owns a trueSrc file at that path.
 * - Member-level entries (`com/Foo.java#bar(int)`): [TRUESRC] is present whenever any
 *   annotation flag or [NEW] applies; the exact set reflects annotations observed on
 *   the trueSrc declaration at merge time.
 */
enum class OriginFlag(val token: String) {
    TRUESRC("TRUESRC"),
    OVERWRITE("OVERWRITE"),
    SHADOW("SHADOW"),
    MODSIG("MODSIG"),
    NEW("NEW"),
    ;

    companion object {
        private val byToken = entries.associateBy { it.token }

        /** Returns the flag for [token], or null if the token is unknown (forward compat). */
        fun fromToken(token: String): OriginFlag? = byToken[token]

        /** Parses a space-separated flag list, dropping unknown tokens. */
        fun parseFlags(flagString: String): Set<OriginFlag> {
            if (flagString.isBlank()) return emptySet()
            val out = java.util.EnumSet.noneOf(OriginFlag::class.java)
            for (tok in flagString.split(' ')) {
                val f = byToken[tok.trim()] ?: continue
                out.add(f)
            }
            return out
        }

        /** Serializes flags in ordinal order for stable diffs. Empty set returns empty string. */
        fun formatFlags(flags: Set<OriginFlag>): String {
            if (flags.isEmpty()) return ""
            return entries.filter { it in flags }.joinToString(" ") { it.token }
        }
    }
}
