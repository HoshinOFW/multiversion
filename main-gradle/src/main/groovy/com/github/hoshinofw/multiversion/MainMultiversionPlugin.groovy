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

class MainMultiversionPlugin implements Plugin<Project> {

    @Override
    void apply(Project target) {
        GeneralUtil.ensureNotNull(target, "Main")

        MultiversionResourcesExtension multiversionResourcesExtension = target.extensions.create("multiversionResources", MultiversionResourcesExtension)

        DefaultProperties.assignIfNeeded(target)
        ApplyExternalPlugins.configure(target)
        MultiversionSubprojectsLogic.configureSubprojects(target, multiversionResourcesExtension)
        MultiversionPatchedSourceGeneration.configure(target)
        Collectors.registerCollectorTasks(target)
        DistributorPublishingConfiguration.configure(target)

        TaskRegistration.registerAll(target)
    }
}
