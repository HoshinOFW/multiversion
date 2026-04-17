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

    /** Internal storage for wireTask declarations (only meaningful on rootProject). */
    List<Map<String, Object>> _wiredTasks = []

    MultiversionProjectExtension(Project project) {
        this.project = project
    }

    /**
     * Wires all {@code :mc_version:module:taskName} into a single root-level {@code :taskName}.
     * If a versioned subproject does not have the task, it is silently skipped.
     *
     * <p>Call this on the root project's {@code multiversion} extension in build.gradle:
     * <pre>
     * multiversion.wireTask 'runClient'
     * multiversion.wireTask 'remapJar', { Project p -> p.name == 'fabric' }
     * </pre>
     *
     * @param taskName The task name to aggregate.
     * @param filter   Optional filter closure ({@code Project -> boolean}). Only matching subprojects are wired.
     */
    void wireTask(String taskName, Closure<Boolean> filter = null) {
        _wiredTasks.add([taskName: taskName, filter: filter])
    }

    /** Returns the module name (the project's directory name, e.g. {@code "fabric"}, {@code "fabric-custom"}). */
    String  module()               { project.name }
    /** Returns true if this project is a declared versioned module (appears in any list in {@code multiversionModules}). */
    boolean isVersionedModule()   { GeneralUtil.isNotBaseVersionModule(project) }
    /** Returns true if this project is enrolled in an {@code architectury*} list and receives full Loom/Arch auto-configuration. */
    boolean isArchEnabled()        { GeneralUtil.isArchEnabled(project) }
    boolean isCommon()            { GeneralUtil.isCommon(project) }
    boolean isFabric()            { GeneralUtil.isFabric(project) }
    boolean isForge()             { GeneralUtil.isForge(project) }
    boolean isNeoForge()          { GeneralUtil.isNeoForge(project) }
    boolean isMcVersion(String v) { GeneralUtil.isMcVersion(project, v) }
    String  mcVersion()           { GeneralUtil.mcVersion(project) }
    String  moduleType()          { GeneralUtil.moduleType(project) }
    String  moduleTypeCapitalized() { GeneralUtil.moduleTypeCapitalized(project) }
}