package com.github.hoshinofw.multiversion

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

/**
 * Project-level extension registered as {@code multiversion} on every project.
 * Provides convenient access to module-type utilities without requiring a static import of GeneralUtil.
 *
 * <p>Usage in build.gradle:
 * <pre>
 * subprojects {
 *     dependencies {
 *         if (multiversion.isFabric()) {
 *             modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"
 *         }
 *         if (multiversion.isForge()) { ... }
 *         if (multiversion.isMcVersion("1.20.1")) { ... }
 *     }
 * }
 * </pre>
 *
 * <p>Also available in individual subproject build.gradle files:
 * <pre>
 * if (multiversion.isNeoForge()) {
 *     // neoforge-specific configuration
 * }
 * </pre>
 */
class MultiversionProjectExtension {

    private final Project project

    MultiversionProjectExtension(Project project) {
        this.project = project
    }

    /** Returns true if this project is a versioned module with Loom applied (common, fabric, forge, or neoforge type). */
    boolean isVersionedModule()   { GeneralUtil.isNotBaseVersionModule(project) }
    boolean isCommon()            { GeneralUtil.isCommon(project) }
    boolean isFabric()            { GeneralUtil.isFabric(project) }
    boolean isForge()             { GeneralUtil.isForge(project) }
    boolean isNeoForge()          { GeneralUtil.isNeoForge(project) }
    boolean isMcVersion(String v) { GeneralUtil.isMcVersion(project, v) }
    String  mcVersion()           { GeneralUtil.mcVersion(project) }
    String  moduleType()          { GeneralUtil.moduleType(project) }
    String  moduleTypeCapitalized() { GeneralUtil.moduleTypeCapitalized(project) }
}