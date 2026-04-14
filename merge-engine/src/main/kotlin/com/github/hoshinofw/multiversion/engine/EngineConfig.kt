package com.github.hoshinofw.multiversion.engine

import java.io.File

/**
 * Configuration written by the Gradle `generateMultiversionEngineConfig` task
 * and read by the IDE plugin for on-save integration.
 *
 * All path fields are absolute strings as serialized by Gradle.
 */
data class EngineConfig(
    val module: String,
    val mcVersion: String,
    val currentSrcDir: String,
    val baseDir: String,
    val patchedOutDir: String,
    val currentSrcRelRoot: String,
    val baseRelRoot: String,
    val originMapFile: String,
) {
    companion object {
        private val ENTRY = Regex(""""([^"\\]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        /**
         * Parses a JSON string produced by [toJson] into an [EngineConfig].
         * Handles standard JSON string escapes.
         */
        @JvmStatic
        fun fromJson(json: String): EngineConfig {
            val v = mutableMapOf<String, String>()
            ENTRY.findAll(json).forEach { m ->
                v[m.groupValues[1]] = unescapeJson(m.groupValues[2])
            }
            fun req(k: String) = v[k] ?: error("missing key '$k' in engine config")
            return EngineConfig(
                module            = req("module"),
                mcVersion         = req("mcVersion"),
                currentSrcDir     = req("currentSrcDir"),
                baseDir           = req("baseDir"),
                patchedOutDir     = req("patchedOutDir"),
                currentSrcRelRoot = req("currentSrcRelRoot"),
                baseRelRoot       = req("baseRelRoot"),
                originMapFile     = req("originMapFile"),
            )
        }

        /** Reads and parses the config from a JSON file. */
        @JvmStatic
        fun fromFile(file: File): EngineConfig = fromJson(file.readText())

        private fun unescapeJson(s: String): String =
            s.replace("\\\\", "\u0000")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\u0000", "\\")

        private fun escapeJson(s: String): String =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
    }

    /** Serializes this config to pretty-printed JSON. */
    fun toJson(): String = buildString {
        appendLine("{")
        val fields = listOf(
            "module"            to module,
            "mcVersion"         to mcVersion,
            "currentSrcDir"     to currentSrcDir,
            "baseDir"           to baseDir,
            "patchedOutDir"     to patchedOutDir,
            "currentSrcRelRoot" to currentSrcRelRoot,
            "baseRelRoot"       to baseRelRoot,
            "originMapFile"     to originMapFile,
        )
        for ((i, pair) in fields.withIndex()) {
            val (key, value) = pair
            val comma = if (i < fields.lastIndex) "," else ""
            appendLine("""    "${escapeJson(key)}": "${escapeJson(value)}"$comma""")
        }
        append("}")
    }

    /** Writes this config as JSON to [file], creating parent directories if needed. */
    fun toFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson())
    }
}
