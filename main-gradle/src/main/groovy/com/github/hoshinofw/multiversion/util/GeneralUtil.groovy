package com.github.hoshinofw.multiversion.util

import com.github.hoshinofw.multiversion.MultiversionConfigurationExtension
import com.github.hoshinofw.multiversion.MultiversionModulesExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider

class GeneralUtil {
    static void ensureNotNull(Object object, String identifier) {
        if (object == null) {throw new NullPointerException("Object of class: ${object.getClass()} was null. Identifier: ${identifier}")}
    }

    static boolean looksLikeMcVersion(String s) {
        return com.github.hoshinofw.multiversion.engine.VersionUtil.looksLikeVersion(s)
    }

    static boolean isValidModLoaderList(String s) {
        List<String> loaders = s.split(",")
        loaders.forEach {
            if (!isValidLoader(it)) return false
        }
        return true
    }

    static boolean isValidLoader(String s) {
        return s == "forge" || s == "neoforge" || s == "fabric"
    }

    /** Returns true if the project path matches {@code :<version>:common}. */
    static boolean isVersionCommon(Project p) {
        isVersionModule(p, 'common')
    }

    /**
     * Returns true if the project path matches {@code :<mcVersion>:<moduleName>}.
     * Used by patchedSrc generation to discover projects for a given module group.
     */
    static boolean isVersionModule(Project p, String moduleName) {
        def parts = p.path.split(':').findAll { it }
        parts.size() == 2 && parts[1] == moduleName && looksLikeMcVersion(parts[0])
    }

    static boolean isMcVersion(Project p, String version) {
        String normalized = version.startsWith(":") ? version : ":${version}:"
        p.path.startsWith(normalized)
    }

    static String resolveValue(Object v, Project p)  {
        if (v instanceof Provider) return v.get()
        if (v instanceof Closure)  return v.call(p)
        if (v instanceof Map)      return resolvePropsMap((Map)v, p)
        return v
    }

    static Map<String, String> resolvePropsMap(Map<String, String> m, Project p) {
        Map<String, String> out = [:]
        m.forEach {String k, String v ->
            out[k] = resolveValue(v, p)
        }
        return out
    }

    /** Returns the actual module name (p.name). Used for archive naming and similar. */
    static String getModLoader(Project p) { return p.name }

    /**
     * Returns the loader type for a project: {@code "common"}, {@code "fabric"},
     * {@code "forge"}, or {@code "neoforge"}. Returns {@code null} if not declared
     * in {@code multiversionModules}.
     */
    static String loaderTypeOf(Project p) {
        return modulesExt(p)?.loaderTypeOf(p.name)
    }

    /** Returns true if the project's loader type is {@code common}. */
    static boolean isCommon(Project p) {
        return modulesExt(p)?.loaderTypeOf(p.name) == 'common'
    }

    /** Returns true if the project's loader type is {@code fabric}. */
    static boolean isFabric(Project p) {
        return modulesExt(p)?.loaderTypeOf(p.name) == 'fabric'
    }

    /** Returns true if the project's loader type is {@code forge}. */
    static boolean isForge(Project p) {
        return modulesExt(p)?.loaderTypeOf(p.name) == 'forge'
    }

    /** Returns true if the project's loader type is {@code neoforge}. */
    static boolean isNeoForge(Project p) {
        return modulesExt(p)?.loaderTypeOf(p.name) == 'neoforge'
    }

    /**
     * Returns true if the project is enrolled in any {@code architectury*} list in
     * {@code multiversionModules}, meaning it receives full Loom/Arch auto-configuration.
     */
    static boolean isArchEnabled(Project p) {
        return modulesExt(p)?.isArchEnabled(p.name) ?: false
    }

    /**
     * Returns the loader type of the project: {@code "common"}, {@code "fabric"},
     * {@code "forge"}, or {@code "neoforge"}. Returns {@code null} if the project
     * is not a recognized versioned module.
     */
    static String moduleType(Project p) {
        String type = modulesExt(p)?.loaderTypeOf(p.name)
        return type in ['common', 'fabric', 'forge', 'neoforge'] ? type : null
    }

    /**
     * Returns the display name of the module type: {@code "Common"}, {@code "Fabric"},
     * {@code "Forge"}, or {@code "NeoForge"}. Returns {@code null} if the project
     * is not a recognized versioned module.
     */
    static String moduleTypeCapitalized(Project p) {
        switch (moduleType(p)) {
            case 'common':   return 'Common'
            case 'fabric':   return 'Fabric'
            case 'forge':    return 'Forge'
            case 'neoforge': return 'NeoForge'
            default:         return null
        }
    }

    /**
     * Properties always required for every versioned module.
     * Used by {@link com.github.hoshinofw.multiversion.resourceExtension.MultiversionResourcesExtension}
     * to populate default resource substitution values.
     */
    static final List<String> minimumBaseProperties = [
            "mod_id",
            "mod_name",
            "mod_version",
            "archives_name",
            "maven_group",
            "minecraft_version",
    ]

    static void ensureMinimumRequiredProperties(Project p) {
        List<String> fullList = new ArrayList<>(minimumBaseProperties)
        if (isArchEnabled(p)) {
            fullList.add("enabled_platforms")
            // fabric_loader_version is only used by common and fabric modules
            if (isCommon(p) || isFabric(p)) fullList.add("fabric_loader_version")
            if (isFabric(p))   fullList.add("fabric_api_version")
            if (isForge(p))    fullList.add("forge_version")
            if (isNeoForge(p)) fullList.add("neoforge_version")
        }
        try {
            ensureProperties(p, fullList)
        } catch (Exception e) {
            throw new Exception("Missing minimum requirements to function:$e")
        }
    }

    static void ensureProperties(Project p, List<String> props) {
        if (isNotBaseVersionModule(p)) {
            for (String propertyName in props) {
                if (!p.hasProperty(propertyName)) throw new Exception("Missing property: $propertyName in project: $p, $p.name")
            }
        }
    }

    static void ensureRootProperties(Project p, List<String> props) {
        for (String propertyName in props) {
            if (!p.hasProperty(propertyName)) throw new Exception("Missing property from root: $propertyName in project: $p, $p.name")
        }
    }

    /**
     * Returns true if the project is a declared versioned module (appears in any plain or
     * architectury list in {@code multiversionModules}) and should receive subproject configuration.
     */
    static boolean isNotBaseVersionModule(Project p) {
        MultiversionModulesExtension mme = modulesExt(p)
        return mme != null && mme.allModules().contains(p.name)
    }

    static String mcVersion(Project p) {
        p.path.split(':').findAll { it }[0]
    }

    static String getTransformProduction(Project p) {
        switch (loaderTypeOf(p)) {
            case 'fabric':   return 'transformProductionFabric'
            case 'forge':    return 'transformProductionForge'
            case 'neoforge': return 'transformProductionNeoForge'
            default:         return null
        }
    }

    static Object configureArchitecturyPlatform(Project p, Object ext) {
        String loaderType = loaderTypeOf(p)
        if (loaderType == 'common') {
            def enabled = (p.findProperty("enabled_platforms"))
                    .toString()
                    .split(',')
                    .collect { it.trim() }
                    .findAll { it }
            ext.common(enabled)
            return
        }

        ext.platformSetupLoomIde()

        switch (loaderType) {
            case 'fabric':   ext.fabric(); break
            case 'forge':    ext.forge(); break
            case 'neoforge': ext.neoForge(); break
        }
    }

    // ---- private helpers ----

    static MultiversionModulesExtension modulesExt(Project p) {
        p.rootProject.extensions.findByType(MultiversionModulesExtension)
    }

    static MultiversionConfigurationExtension configExt(Project p) {
        p.rootProject.extensions.findByType(MultiversionConfigurationExtension)
    }
}
