package com.github.hoshinofw.multiversion.tasks

import org.gradle.api.Project

class TaskRegistration {

    static void registerAll(Project root) {
        InitVersionTask.register(root)
    }

}
