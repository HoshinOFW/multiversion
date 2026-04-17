package com.github.hoshinofw.multiversion.tasks

import com.github.hoshinofw.multiversion.MultiversionProjectExtension
import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project
import org.gradle.api.Task

class TaskRegistration {

    static void registerAll(Project root) {
        InitVersionTask.register(root)
        registerWiredTasks(root)
    }

    private static void registerWiredTasks(Project root) {
        def ext = root.extensions.findByType(MultiversionProjectExtension)
        if (ext == null || ext._wiredTasks.isEmpty()) return

        def versionedSubs = root.subprojects.findAll { Project sp ->
            def parts = sp.path.split(':').findAll { it }
            parts.size() == 2 && GeneralUtil.looksLikeMcVersion(parts[0])
        }

        ext._wiredTasks.each { Map<String, Object> entry ->
            String taskName = entry.taskName
            Closure<Boolean> filter = entry.filter as Closure<Boolean>

            def candidates = versionedSubs.findAll { Project sp ->
                filter == null || filter.call(sp)
            }

            if (candidates.isEmpty()) return

            // Register a root-level aggregator task (or configure existing one).
            Task rootTask
            if (root.tasks.findByName(taskName) != null) {
                rootTask = root.tasks.getByName(taskName)
            } else {
                rootTask = root.tasks.create(taskName) { t ->
                    t.group = "multiversion"
                    t.description = "Runs :${taskName} across wired versioned subprojects."
                }
            }

            // Wire each candidate subproject's task into the root task.
            // Use afterEvaluate on each subproject so external plugin tasks (e.g. Loom)
            // are registered by the time we check. Subprojects without the task are skipped.
            candidates.each { Project sp ->
                sp.afterEvaluate {
                    def subTask = sp.tasks.findByName(taskName)
                    if (subTask != null) {
                        rootTask.dependsOn(subTask)
                    }
                }
            }
        }
    }

}
