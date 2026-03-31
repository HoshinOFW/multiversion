package com.github.hoshinofw.multiversion.collecting

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class Collectors {

    static void registerCollectorTasks(Project root) {
        GeneralUtil.ensureNotNull(root, "CollectorTasks")

        def collectableSubs = root.subprojects.findAll { Project sp ->
            def parts = sp.path.split(':').findAll { it }
            parts.size() == 2 && GeneralUtil.looksLikeMcVersion(parts[0])
        }

        if (collectableSubs.isEmpty()) {
            root.logger.lifecycle("[multiversion] No versioned loader projects found; no collector tasks registered.")
            return
        }

        root.tasks.register("collectBuildsAll") { t ->
            t.group = "distribution"
            t.description = "Collects all built jars for all versions/loaders."
            t.dependsOn(collectableSubs.collect { sp -> "${sp.path}:collectBuilds" })
        }
    }

}
