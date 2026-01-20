package com.github.hoshinofw.multiversion.javaConfigure

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

class JavaConfiguration {

    static void configure(Project p) {
        p.java {
            withSourcesJar()
            if (GeneralUtil.is120(p)) {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            } else {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }

        p.tasks.withType(JavaCompile).configureEach {
            if (GeneralUtil.is120(p)) {
                it.options.release = 17
            } else if (GeneralUtil.is121(p)) {
                it.options.release = 21
            } else {
                it.options.release = 21
            }
        }
    }

}
