package com.github.hoshinofw.multiversion

/**
 * DSL extension registered as {@code multiversionModules { }} in the root build.gradle.
 *
 * <p>Declares which module names exist, what loader type each belongs to, and which should
 * have patchedSrc generation applied across versions.
 *
 * <p>Example:
 * <pre>
 * multiversionModules {
 *     common   = ['common', 'api']
 *     fabric   = ['fabric']
 *     forge    = ['forge']
 *     neoforge = ['neoforge']
 *     patchModules = ['common', 'fabric', 'forge', 'neoforge']
 * }
 * </pre>
 *
 * <p>Modules listed under {@code common/fabric/forge/neoforge} receive the corresponding
 * Architectury/Loom configuration. Modules listed in {@code patchModules} additionally
 * receive patchedSrc generation across versions. Patching is disabled when
 * {@code patchModules} is empty.
 *
 * <p>Standard module names (common, fabric, forge, neoforge) always receive their
 * default configuration regardless of whether they appear in this extension, preserving
 * backward compatibility.
 */
class MultiversionModulesExtension {

    /** Module names that receive common-type configuration (Fabric loader, Architectury annotations, no platform Loom). */
    List<String> common = []

    /** Module names that receive Fabric Loom configuration. */
    List<String> fabric = []

    /** Module names that receive Forge Loom configuration. */
    List<String> forge = []

    /** Module names that receive NeoForge Loom configuration. */
    List<String> neoforge = []

    /**
     * Module names that get patchedSrc generation across versions.
     * Must be a subset of names declared in the loader-type lists above.
     * Patching is disabled when this list is empty.
     */
    List<String> patchModules = []

    /** Returns true if any loader-type group has been configured. */
    boolean isConfigured() {
        !common.isEmpty() || !fabric.isEmpty() || !forge.isEmpty() || !neoforge.isEmpty()
    }

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

    /** Returns all module names declared across all loader-type groups. */
    List<String> allModules() {
        def all = [] as List<String>
        all.addAll(common)
        all.addAll(fabric)
        all.addAll(forge)
        all.addAll(neoforge)
        return all
    }
}
