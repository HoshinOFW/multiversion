package com.github.hoshinofw.multiversion.util

import com.github.hoshinofw.multiversion.MultiversionModulesExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider

class GeneralUtil {
    static void ensureNotNull(Object object, String identifier) {
        if (object == null) {throw new NullPointerException("Object of class: ${object.getClass()} was null. Identifier: ${identifier}")}
    }

    static boolean looksLikeMcVersion(String s) {
        return s ==~ /\d+(\.\d+){1,3}/
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
        parts.size() == 2 && parts[1] == moduleName && (parts[0] ==~ /\d+(\.\d+){1,3}/)
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
     * {@code "forge"}, or {@code "neoforge"}.
     *
     * <p>When {@code multiversionModules} is configured, the type is looked up from that
     * extension. Falls back to {@code p.name} for standard module names to preserve
     * backward compatibility.
     */
    static String loaderTypeOf(Project p) {
        MultiversionModulesExtension mme = modulesExt(p)
        if (mme?.isConfigured()) {
            String type = mme.loaderTypeOf(p.name)
            if (type != null) return type
        }
        return p.name
    }

    /**
     * Returns true if the project is a common-type module.
     *
     * <p>Always true for {@code p.name == "common"} (backward compat). Additionally true
     * for any module name listed under {@code multiversionModules { common = [...] }}.
     */
    static boolean isCommon(Project p) {
        if (p.name == 'common') return true
        MultiversionModulesExtension mme = modulesExt(p)
        return mme?.isConfigured() && mme.loaderTypeOf(p.name) == 'common'
    }

    static boolean isFabric(Project p) {
        if (p.name == 'fabric') return true
        MultiversionModulesExtension mme = modulesExt(p)
        return mme?.isConfigured() && mme.loaderTypeOf(p.name) == 'fabric'
    }

    static boolean isForge(Project p) {
        if (p.name == 'forge') return true
        MultiversionModulesExtension mme = modulesExt(p)
        return mme?.isConfigured() && mme.loaderTypeOf(p.name) == 'forge'
    }

    static boolean isNeoForge(Project p) {
        if (p.name == 'neoforge') return true
        MultiversionModulesExtension mme = modulesExt(p)
        return mme?.isConfigured() && mme.loaderTypeOf(p.name) == 'neoforge'
    }

    static final List<String> minimumRequiredProperties = [
            "mod_id",
            "mod_name",
            "mod_version",
            "archives_name",
            "maven_group",
            "minecraft_version",
            "enabled_platforms",
            "fabric_loader_version",
            "architectury_api_version"
    ]

    static void ensureMinimumRequiredProperties(Project p){
        List<String> fullList = new ArrayList<>(minimumRequiredProperties)
        if (isFabric(p)) {fullList.add("fabric_api_version")}
        if (isForge(p)) {fullList.add("forge_version")}
        if (isNeoForge(p)) {fullList.add("neoforge_version")}
        try {
            ensureProperties(p, fullList)
        } catch (Exception e){
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
     * Returns true if the project should receive full Architectury/Loom subproject configuration.
     *
     * <p>Always true for the four standard module names (backward compat). Additionally true
     * for any module name declared in {@code multiversionModules}.
     */
    static boolean isNotBaseVersionModule(Project p) {
        if (p.name in ['common', 'fabric', 'forge', 'neoforge']) return true
        MultiversionModulesExtension mme = modulesExt(p)
        return mme?.isConfigured() && mme.allModules().contains(p.name)
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

    private static MultiversionModulesExtension modulesExt(Project p) {
        p.rootProject.extensions.findByType(MultiversionModulesExtension)
    }
}
