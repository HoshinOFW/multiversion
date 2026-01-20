package com.github.hoshinofw.multiversion.resourceExtension

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.annotations.NotNull

class MultiversionResourcesExtension {

    @NotNull List<String> filesMatching = []
    final defaultFilesMatching = ['META-INF/mods.toml', 'fabric.mod.json', 'META-INF/neoforge.mods.toml']

    @NotNull Map<String, String> replaceProperties = new HashMap<>()

    static Map<String, String> generateDefaultReplaceProperties(@NotNull Project root) {
        Map<String, String> out =  new HashMap<>()
        GeneralUtil.minimumRequiredProperties.forEach {
            out.put(it, root.findProperty(it).toString())
        }
        return out
    }

    static Map<String, String> generateReplaceProperties(@NotNull Project root, MultiversionResourcesExtension mrt) {
        Map<String, String> out = generateDefaultReplaceProperties(root)
        if (mrt?.replaceProperties) {
            out.putAll(mrt.replaceProperties)
        }
        return out
    }

    static void configure(Project p, Project root, MultiversionResourcesExtension mrt) {
        p.tasks.named("processResources", ProcessResources).configure { ProcessResources t ->
            Map<String, String> replaceProperties = generateReplaceProperties(p, mrt)
            Map<String, String> resolved = GeneralUtil.resolvePropsMap(replaceProperties, p)

            t.inputs.properties(resolved)
            t.filteringCharset = "UTF-8"

            List<String> patterns = []
            patterns.addAll(mrt.filesMatching)
            patterns.addAll(mrt.defaultFilesMatching)

            t.filesMatching(patterns) {
                it.expand(resolved)
            }
        }
    }

}
