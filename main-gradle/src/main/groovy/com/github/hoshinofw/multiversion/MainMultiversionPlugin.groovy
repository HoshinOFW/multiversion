package com.github.hoshinofw.multiversion

import com.github.hoshinofw.multiversion.collecting.Collectors
import com.github.hoshinofw.multiversion.patching.MultiversionPatchedSourceGeneration
import com.github.hoshinofw.multiversion.pluginsExternal.ApplyExternalPlugins
import com.github.hoshinofw.multiversion.properties.DefaultProperties
import com.github.hoshinofw.multiversion.publishing.DistributorPublishingConfiguration
import com.github.hoshinofw.multiversion.resourceExtension.MultiversionResourcesExtension
import com.github.hoshinofw.multiversion.subprojects.MultiversionSubprojectsLogic
import com.github.hoshinofw.multiversion.tasks.TaskRegistration
import com.github.hoshinofw.multiversion.util.GeneralUtil
import dev.architectury.plugin.ArchitectPluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

class MainMultiversionPlugin implements Plugin<Project> {

    @Override
    void apply(Project target) {
        GeneralUtil.ensureNotNull(target, "Main")

        MultiversionResourcesExtension multiversionResourcesExtension = target.extensions.create("multiversionResources", MultiversionResourcesExtension)
        MultiversionModulesExtension multiversionModulesExtension = target.extensions.create("multiversionModules", MultiversionModulesExtension)

        // Always register the anchor task so the beforeSync IDE trigger never breaks sync,
        // even if the plugin is later removed or patchModules is emptied.
        TaskProvider<Task> genAll = target.tasks.register("generateAllPatchedSrc") {
            it.group = "build setup"
            it.description = "Generates all patchedSrc trees for IDE sync/import."
        }

        target.plugins.withId("org.jetbrains.gradle.plugin.idea-ext") {
            target.idea.project.settings {
                it.taskTriggers {
                    it.beforeSync(genAll)
                }
            }
        }

        DefaultProperties.assignIfNeeded(target)
        ApplyExternalPlugins.configure(target)
        MultiversionSubprojectsLogic.configureSubprojects(target, multiversionResourcesExtension)
        MultiversionPatchedSourceGeneration.configure(target, multiversionModulesExtension)
        Collectors.registerCollectorTasks(target)
        DistributorPublishingConfiguration.configure(target)

        TaskRegistration.registerAll(target)
    }
}
