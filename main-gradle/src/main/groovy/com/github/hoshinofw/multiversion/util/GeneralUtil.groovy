package com.github.hoshinofw.multiversion.util

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

    static boolean isVersionCommon(Project p) {
        def parts = p.path.split(':').findAll { it }
        parts.size() == 2 && parts[1] == 'common' && (parts[0] ==~ /\d+(\.\d+){1,3}/)
    }

    static boolean is120(Project p) { p.path.startsWith(':1.20.1:') }
    static boolean is121(Project p) { p.path.startsWith(':1.21.1:') }

    static boolean isMcVersion(Project p, String string) {p.path.startsWith(string)}

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

    static String getModLoader(Project p) {return p.name}

    static boolean isCommon(Project p) {  p.name == "common" }
    static boolean isFabric(Project p) { p.name == "fabric" }
    static boolean isForge(Project p) { p.name == "forge" }
    static boolean isNeoForge(Project p) {p.name == "neoforge" }

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

    static boolean isNotBaseVersionModule(Project p) {isCommon(p) || isFabric(p) || isForge(p) || isNeoForge(p)}

    static String mcVersion(Project p) {
        p.path.split(':').findAll { it }[0]
    }

    static String getTransformProduction(Project p) {
        if (isCommon(p)) {null}
        if (isFabric(p)) {return "transformProductionFabric"}
        if (isForge(p)) {return "transformProductionForge"}
        if (isNeoForge(p)) {return "transformProductionNeoForge"}
        return null
    }

     static Object configureArchitecturyPlatform (Project p, Object ext) {
        if (p.name == "common") {
            def enabled = (p.findProperty(("enabled_platforms")))
                    .toString()
                    .split(',')
                    .collect { it.trim() }
                    .findAll { it }

            ext.common(enabled)
            return
        }

        ext.platformSetupLoomIde()

        switch (p.name) {
            case "fabric":   ext.fabric(); break
            case "forge":    ext.forge(); break
            case "neoforge": ext.neoForge(); break
        }
    }
}
