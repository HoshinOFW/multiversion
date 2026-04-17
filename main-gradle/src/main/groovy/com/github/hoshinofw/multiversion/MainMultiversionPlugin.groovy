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
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

class MainMultiversionPlugin implements Plugin<Project> {

    @Override
    void apply(Project target) {
        GeneralUtil.ensureNotNull(target, "Main")

        MultiversionResourcesExtension multiversionResourcesExtension = target.extensions.create("multiversionResources", MultiversionResourcesExtension)
        // Read the modules extension from the settings plugin (registered on gradle.ext).
        // Falls back to creating a local one for projects that haven't migrated to settings.gradle yet.
        MultiversionModulesExtension multiversionModulesExtension =
                target.gradle.ext.has("multiversionModules")
                        ? target.gradle.ext.get("multiversionModules") as MultiversionModulesExtension
                        : target.extensions.create("multiversionModules", MultiversionModulesExtension)
        target.extensions.create("multiversionConfiguration", MultiversionConfigurationExtension)

        // Register on every project so multiversion.isFabric() etc. resolve correctly
        // both inside subprojects{} blocks and in individual subproject build.gradle files.
        target.allprojects { p ->
            p.extensions.create("multiversion", MultiversionProjectExtension, p)
        }

        TaskProvider<Task> genAll = target.tasks.register("generateAllPatchedSrc") {
            it.group = "build setup"
            it.description = "Generates all patchedSrc trees for IDE sync/import."
        }

        target.afterEvaluate {
            multiversionModulesExtension.validate()
            DefaultProperties.assignIfNeeded(target)
            ApplyExternalPlugins.configure(target)
            MultiversionSubprojectsLogic.configureSubprojects(target, multiversionResourcesExtension)
            MultiversionPatchedSourceGeneration.configure(target, multiversionModulesExtension)
            Collectors.registerCollectorTasks(target)
            DistributorPublishingConfiguration.configure(target)
            TaskRegistration.registerAll(target)
        }
    }
}
