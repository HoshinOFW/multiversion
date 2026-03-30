package com.github.hoshinofw.multiversion.javaConfigure

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

class JavaConfiguration {

    static void configure(Project p) {
        p.java {
            withSourcesJar()
            if (GeneralUtil.isMcVersion(p, "1.20.1")) {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            } else {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }

        p.tasks.withType(JavaCompile).configureEach {
            it.options.release = GeneralUtil.isMcVersion(p, "1.20.1") ? 17 : 21
        }
    }

}
