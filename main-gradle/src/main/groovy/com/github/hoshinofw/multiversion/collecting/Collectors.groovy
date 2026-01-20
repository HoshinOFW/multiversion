package com.github.hoshinofw.multiversion.collecting

import com.github.hoshinofw.multiversion.util.CollectionUtil
import com.github.hoshinofw.multiversion.util.GeneralUtil
import com.github.hoshinofw.multiversion.util.PatchingUtil
import com.github.hoshinofw.multiversion.util.PublishUtil
import org.gradle.api.Project

class Collectors {

    static void registerCollectorTasks(Project root) {
        GeneralUtil.ensureNotNull(root, "CollectorTasks")

        Map<String, LinkedHashSet<String>> versionToLoaders = [:].withDefault { new LinkedHashSet<String>() }

        root.subprojects.each { Project sp ->
            def parts = sp.path.split(':').findAll { it }
            if (parts.size() != 2) return

            def mc = parts[0]
            def loader = parts[1]

            if (!GeneralUtil.looksLikeMcVersion(mc)) return
            versionToLoaders[mc].add(loader)
        }

        if (versionToLoaders.isEmpty()) {
            root.logger.lifecycle("[multiversion] No versioned loader projects found; no collector tasks registered.")
            return
        }

        versionToLoaders.each { String mc, Set<String> loaders ->
            loaders.each { String loader ->
                String projectPath = ":${mc}:${loader}"
                if (root.findProject(projectPath) == null) return

                CollectionUtil.collectJarFrom(root, mc, loader, projectPath)
            }
        }

        def perVersionTaskNames = []

        versionToLoaders.keySet().toList().sort(CollectionUtil::compareMcVersions).each { String mc ->
            def loaders = versionToLoaders[mc].toList().sort()

            def aggregateName = "collectBuilds_${mc.replace('.', '_')}"
            perVersionTaskNames << aggregateName

            root.tasks.register(aggregateName) { t ->
                t.group = "distribution"
                t.description = "Collects all built jars for MC ${mc}"

                def deps = loaders.collect { loader -> "collect_${mc.replace('.', '_')}_${loader}" }
                t.dependsOn(deps)
            }
        }

        // Global aggregate
        root.tasks.register("collectBuildsAll") { t ->
            t.group = "distribution"
            t.description = "Collects all built jars for all discovered versions/loaders"
            t.dependsOn(perVersionTaskNames)
        }
    }

}
