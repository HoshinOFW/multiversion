package com.github.hoshinofw.multiversion

/**
 * DSL extension registered as {@code multiversionModules { }} in settings.gradle.
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
 * <p>Example -- all modules use Architectury/Loom:
 * <pre>
 * multiversionModules {
 *     architecturyCommon   = ['common']
 *     architecturyFabric   = ['fabric']
 *     architecturyNeoforge = ['neoforge']
 *     patchModules = ['common', 'fabric', 'neoforge']
 * }
 * </pre>
 *
 * <p>Example -- mixed: one plain module, others use Loom:
 * <pre>
 * multiversionModules {
 *     common   = ['api']          // plain -- no Loom, user manages build setup
 *     architecturyCommon   = ['common']
 *     architecturyFabric   = ['fabric']
 *     architecturyNeoforge = ['neoforge']
 *     patchModules = ['api', 'common', 'fabric', 'neoforge']
 * }
 * </pre>
 *
 * <h3>Version discovery configuration</h3>
 * <p>These fields control how the settings plugin discovers version directories:
 * <ul>
 *   <li>{@code versionPattern} -- custom regex for matching version directory names
 *       (default: {@code ^\d+(\.\d+){1,3}$})</li>
 *   <li>{@code versions} -- explicit ordered version list; when set, skips filesystem scanning</li>
 * </ul>
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

    // ---- Version discovery configuration ----

    /**
     * Custom regex pattern for matching version directory names.
     * When {@code null}, the default pattern {@code ^\d+(\.\d+){1,3}$} is used.
     */
    String versionPattern = null

    /**
     * Explicit ordered list of version strings. When set (non-null), the settings plugin
     * uses this list directly instead of scanning the filesystem for version directories.
     * Each entry must correspond to a directory name under the project root.
     */
    List<String> versions = null

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
