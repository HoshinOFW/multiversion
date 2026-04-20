package com.github.hoshinofw.multiversion.idea_plugin.project

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class PluginConfigurable : BoundConfigurable("Multiversion") {
    override fun createPanel(): DialogPanel {
        val settings = PluginSettings.getInstance()
        return panel {
            group("Refactoring") {
                row {
                    checkBox("Propagate refactoring to other Minecraft versions")
                        .bindSelected(settings::propagateRefactoring)
                }
            }
        }
    }
}