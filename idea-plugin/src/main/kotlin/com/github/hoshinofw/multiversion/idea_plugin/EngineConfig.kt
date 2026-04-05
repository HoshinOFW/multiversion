package com.github.hoshinofw.multiversion.idea_plugin

/**
 * Mirrors the JSON written by the Gradle `generateMultiversionEngineConfig` task.
 * All path fields are absolute strings as serialised by Gradle.
 */
data class EngineConfig(
    val module: String,
    val mcVersion: String,
    val currentSrcDir: String,
    val baseDir: String,
    val patchedOutDir: String,
    val currentSrcRelRoot: String,
    val baseRelRoot: String,
    val originMapFile: String
)