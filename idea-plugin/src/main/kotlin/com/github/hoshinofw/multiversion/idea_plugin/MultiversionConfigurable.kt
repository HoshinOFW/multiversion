package com.github.hoshinofw.multiversion.idea_plugin

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class MultiversionConfigurable : BoundConfigurable("Multiversion") {
    override fun createPanel(): DialogPanel {
        val settings = MultiversionSettings.getInstance()
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