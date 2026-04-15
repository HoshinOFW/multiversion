package com.github.hoshinofw.multiversion

/**
 * DSL extension registered as {@code multiversionModules { }} in the root build.gradle.
 *
 * <p>Each module name must appear in exactly one plain loader-type list. Plain lists drive
 * all routing utilities ({@code isFabric()}, {@code moduleType()}, etc.) and determine
 * which modules receive {@code multiversionResources} processing and patchedSrc generation.
 *
 * <p>To additionally opt a module into full Architectury/Loom auto-configuration
 * (minecraft dependency, mappings, loader deps, remapJar, shadow-bundling), add the
 * module name to the corresponding {@code architectury*} list. Listing a module in an
 * {@code architectury*} list implicitly adds it to the matching plain list, so there is
 * no need to declare it twice.
 *
 * <p>Example — all modules use Architectury/Loom:
 * <pre>
 * multiversionModules {
 *     architecturyCommon   = ['common']
 *     architecturyFabric   = ['fabric']
 *     architecturyNeoforge = ['neoforge']
 *     patchModules = ['common', 'fabric', 'neoforge']
 * }
 * </pre>
 *
 * <p>Example — mixed: one plain module, others use Loom:
 * <pre>
 * multiversionModules {
 *     common   = ['api']          // plain — no Loom, user manages build setup
 *     architecturyCommon   = ['common']
 *     architecturyFabric   = ['fabric']
 *     architecturyNeoforge = ['neoforge']
 *     patchModules = ['api', 'common', 'fabric', 'neoforge']
 * }
 * </pre>
 */
class MultiversionModulesExtension {

    // ---- Plain loader-type lists (routing only) ----

    /** Module names whose loader type is {@code common}. */
    List<String> common = []

    /** Module names whose loader type is {@code fabric}. */
    List<String> fabric = []

    /** Module names whose loader type is {@code forge}. */
    List<String> forge = []

    /** Module names whose loader type is {@code neoforge}. */
    List<String> neoforge = []

    // ---- Architectury/Loom opt-in lists ----

    /**
     * Subset of {@code common} modules that receive full Architectury/Loom auto-configuration.
     * These modules are shadow-bundled into platform modules.
     */
    List<String> architecturyCommon = []

    /** Subset of {@code fabric} modules that receive full Architectury/Loom auto-configuration. */
    List<String> architecturyFabric = []

    /** Subset of {@code forge} modules that receive full Architectury/Loom auto-configuration. */
    List<String> architecturyForge = []

    /** Subset of {@code neoforge} modules that receive full Architectury/Loom auto-configuration. */
    List<String> architecturyNeoforge = []

    // ---- Patching ----

    /**
     * Module names that get patchedSrc generation across versions.
     * Fully independent of the loader-type and architectury lists.
     * Patching is disabled when this list is empty.
     */
    List<String> patchModules = []

    // ---- Task wiring ----

    /** Internal storage for wireTask declarations. */
    List<Map<String, Object>> _wiredTasks = []

    /**
     * Wires all {@code :mc_version:module:taskName} into a single root-level {@code :taskName}.
     * If a versioned subproject does not have the task, it is silently skipped.
     *
     * <p>Example:
     * <pre>
     * multiversionModules {
     *     wireTask 'runClient'
     *     wireTask 'remapJar', { Project p -> p.name == 'fabric' }
     * }
     * </pre>
     *
     * @param taskName The task name to aggregate.
     * @param filter   Optional filter closure ({@code Project -> boolean}). Only matching subprojects are wired.
     */
    void wireTask(String taskName, Closure<Boolean> filter = null) {
        _wiredTasks.add([taskName: taskName, filter: filter])
    }

    // ---- Queries ----

    /**
     * Returns the loader type string for {@code moduleName}, or {@code null} if unknown.
     * Possible return values: {@code "common"}, {@code "fabric"}, {@code "forge"}, {@code "neoforge"}.
     */
    String loaderTypeOf(String moduleName) {
        if (common.contains(moduleName))   return 'common'
        if (fabric.contains(moduleName))   return 'fabric'
        if (forge.contains(moduleName))    return 'forge'
        if (neoforge.contains(moduleName)) return 'neoforge'
        return null
    }

    /** Returns true if {@code moduleName} is enrolled in any {@code architectury*} list. */
    boolean isArchEnabled(String moduleName) {
        architecturyCommon.contains(moduleName)   ||
        architecturyFabric.contains(moduleName)   ||
        architecturyForge.contains(moduleName)    ||
        architecturyNeoforge.contains(moduleName)
    }

    /** Returns all module names declared across all plain loader-type lists (deduplicated). */
    List<String> allModules() {
        (common + fabric + forge + neoforge).unique()
    }

    /** Returns all module names declared across all {@code architectury*} lists (deduplicated). */
    List<String> allArchitecturyModules() {
        (architecturyCommon + architecturyFabric + architecturyForge + architecturyNeoforge).unique()
    }

    /**
     * Merges each {@code architectury*} list into its corresponding plain list so that
     * enrolling a module in e.g. {@code architecturyFabric} implicitly declares it as a
     * {@code fabric}-type module without requiring the user to list it twice.
     *
     * <p>Called automatically in {@code afterEvaluate} by the plugin.
     */
    void validate() {
        architecturyCommon.each   { if (!common.contains(it))   common.add(it) }
        architecturyFabric.each   { if (!fabric.contains(it))   fabric.add(it) }
        architecturyForge.each    { if (!forge.contains(it))    forge.add(it) }
        architecturyNeoforge.each { if (!neoforge.contains(it)) neoforge.add(it) }
    }
}