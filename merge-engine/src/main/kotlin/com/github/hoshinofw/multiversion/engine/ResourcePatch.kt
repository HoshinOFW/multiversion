package com.github.hoshinofw.multiversion.engine

import java.io.File

/**
 * Configuration for resource patching loaded from `multiversion-resources.json`.
 */
data class ResourcePatchConfig(
    val deletes: List<String>,
    val moves: List<MoveEntry>,
) {
    data class MoveEntry(val from: String, val to: String)

    companion object {
        /** Returns an empty config (no operations). */
        @JvmStatic
        fun empty(): ResourcePatchConfig = ResourcePatchConfig(emptyList(), emptyList())

        const val DEFAULT_FILENAME = "multiversion-resources.json"

        /**
         * Loads the resource patch config from [resourcesDir] using the given [filename].
         * Returns [empty] if the file is absent or malformed.
         */
        @JvmStatic
        @JvmOverloads
        fun fromDirectory(resourcesDir: File, filename: String = DEFAULT_FILENAME): ResourcePatchConfig {
            val f = File(resourcesDir, filename)
            if (!f.exists()) return empty()
            return fromJson(f.readText())
        }

        /**
         * Parses a `multiversion-resources.json` string.
         * Returns [empty] if the content is malformed.
         */
        @JvmStatic
        fun fromJson(json: String): ResourcePatchConfig {
            return try {
                parseJson(json)
            } catch (_: Exception) {
                empty()
            }
        }

        private fun parseJson(json: String): ResourcePatchConfig {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return empty()

            val deletes = mutableListOf<String>()
            val moves = mutableListOf<MoveEntry>()

            // Extract "delete" array
            val deleteArray = extractJsonArray(trimmed, "delete")
            if (deleteArray != null) {
                for (value in parseStringArray(deleteArray)) {
                    val normalized = value.replace('\\', '/').trim()
                    if (normalized.isNotEmpty()) deletes.add(normalized)
                }
            }

            // Extract "move" array
            val moveArray = extractJsonArray(trimmed, "move")
            if (moveArray != null) {
                for (obj in parseObjectArray(moveArray)) {
                    val from = extractStringValue(obj, "from")?.replace('\\', '/')?.trim() ?: continue
                    val to = extractStringValue(obj, "to")?.replace('\\', '/')?.trim() ?: continue
                    if (from.isNotEmpty() && to.isNotEmpty()) {
                        moves.add(MoveEntry(from, to))
                    }
                }
            }

            return ResourcePatchConfig(deletes, moves)
        }

        // Minimal JSON extraction helpers for flat structures.

        private fun extractJsonArray(json: String, key: String): String? {
            val pattern = """"${Regex.escape(key)}"\s*:\s*\["""
            val match = Regex(pattern).find(json) ?: return null
            val start = match.range.last
            var depth = 1
            var i = start + 1
            while (i < json.length && depth > 0) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> depth--
                    '"' -> { i++; while (i < json.length && json[i] != '"') { if (json[i] == '\\') i++; i++ } }
                }
                i++
            }
            return json.substring(start, i)
        }

        private fun parseStringArray(arrayContent: String): List<String> {
            val result = mutableListOf<String>()
            val stringPattern = Regex(""""((?:[^"\\]|\\.)*)"""")
            for (match in stringPattern.findAll(arrayContent)) {
                result.add(unescapeJsonString(match.groupValues[1]))
            }
            return result
        }

        private fun parseObjectArray(arrayContent: String): List<String> {
            val objects = mutableListOf<String>()
            var depth = 0
            var start = -1
            for (i in arrayContent.indices) {
                when (arrayContent[i]) {
                    '{' -> { if (depth == 0) start = i; depth++ }
                    '}' -> { depth--; if (depth == 0 && start >= 0) { objects.add(arrayContent.substring(start, i + 1)); start = -1 } }
                    '"' -> { val j = i + 1; var k = j; while (k < arrayContent.length && arrayContent[k] != '"') { if (arrayContent[k] == '\\') k++; k++ } }
                }
            }
            return objects
        }

        private fun extractStringValue(obj: String, key: String): String? {
            val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val match = pattern.find(obj) ?: return null
            return unescapeJsonString(match.groupValues[1])
        }

        private fun unescapeJsonString(s: String): String =
            s.replace("\\\\", "\u0000")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\u0000", "\\")
    }
}

/**
 * Applies resource patch operations (delete/move) to a directory.
 */
object ResourcePatchEngine {

    /**
     * Applies [deletes] and [moves] to [outResDir] in place.
     *
     * Deletion is skipped for any path that [currentVersionFiles] explicitly re-provides,
     * so a newer version can un-delete a resource by including it in its own source tree.
     * Moves are always applied when the source path exists; if the destination was explicitly
     * provided by the current version, the moved content is discarded but the old path is
     * still removed.
     *
     * @param outResDir          The output resources directory to modify in place.
     * @param deletes            Relative paths to delete. Paths ending with '/' are directories.
     * @param moves              Ordered list of from/to relative path pairs.
     * @param currentVersionFiles Relative paths explicitly provided by the current version.
     */
    @JvmStatic
    fun applyResourcePatch(
        outResDir: File,
        deletes: List<String>,
        moves: List<ResourcePatchConfig.MoveEntry>,
        currentVersionFiles: Set<String>,
    ) {
        // Apply deletes
        for (rel in deletes) {
            val r = rel.replace('\\', '/').trim()
            if (r.isEmpty()) continue

            val isDir = r.endsWith('/')
            val fsRel = if (isDir) r.substring(0, r.length - 1) else r

            // Skip if the current version explicitly re-provides this path
            if (isDir) {
                if (currentVersionFiles.any { it.startsWith(r) }) continue
            } else {
                if (currentVersionFiles.contains(r)) continue
            }

            val target = File(outResDir, fsRel)
            if (!target.exists()) continue
            if (target.isDirectory) target.deleteRecursively() else target.delete()
        }

        // Apply moves
        for (entry in moves) {
            val fromRel = entry.from.replace('\\', '/').trim()
            val toRel = entry.to.replace('\\', '/').trim()
            if (fromRel.isEmpty() || toRel.isEmpty()) continue

            val fromFile = File(outResDir, fromRel)
            if (!fromFile.exists()) continue

            if (currentVersionFiles.contains(toRel)) {
                // Current version provides the destination; discard moved content but remove old path
                fromFile.delete()
                continue
            }

            val toFile = File(outResDir, toRel)
            toFile.parentFile?.mkdirs()
            fromFile.copyTo(toFile, overwrite = true)
            fromFile.delete()
        }
    }

    /**
     * Convenience overload that takes a [ResourcePatchConfig] directly.
     */
    @JvmStatic
    fun applyResourcePatch(
        outResDir: File,
        config: ResourcePatchConfig,
        currentVersionFiles: Set<String>,
    ) {
        applyResourcePatch(outResDir, config.deletes, config.moves, currentVersionFiles)
    }
}
