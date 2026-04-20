package com.github.hoshinofw.multiversion.idea_plugin.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "MultiversionSettings", storages = [Storage("multiversion.xml")])
@Service(Service.Level.APP)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(var propagateRefactoring: Boolean = true)

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    var propagateRefactoring: Boolean
        get() = myState.propagateRefactoring
        set(value) { myState.propagateRefactoring = value }

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
