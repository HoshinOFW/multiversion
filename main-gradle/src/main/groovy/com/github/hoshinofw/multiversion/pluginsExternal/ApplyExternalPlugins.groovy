package com.github.hoshinofw.multiversion.pluginsExternal

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class ApplyExternalPlugins {

    static void configure(Project root) {
        GeneralUtil.ensureNotNull(root, "PluginApplication")
        root.pluginManager.apply("me.modmuss50.mod-publish-plugin")
        root.pluginManager.apply("org.jetbrains.gradle.plugin.idea-ext" )
    }

}
