package com.github.hoshinofw.multiversion.util

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.internal.impldep.org.apache.commons.lang.NullArgumentException

import java.util.function.BooleanSupplier

class PublishUtil {

    static Boolean requirePublishFlag(Project p) {
        if (p.hasProperty("PUBLISH_RELEASE")) {
            return p.findProperty("PUBLISH_RELEASE") == "true"
        }
        return false
    }

    static String readChangelog(Project root) {
        GeneralUtil.ensureNotNull(root, "ReadChangelog")
        def f = root.file("changelog.md")
        return f.exists() ? f.getText("UTF-8") : "No changelog.md found."
    }

    static def singleJarFromDir(Project root, String dirPath) {
        root.providers.provider {
            GeneralUtil.ensureNotNull(root, "SingleJarFromDir")
            def dir = root.file(dirPath)
            def ver = root.mod_version.toString()

            def tree = root.fileTree(dir) {
                include("*-${ver}.jar")
                exclude("*-sources.jar", "*-javadoc.jar", "*-shadow.jar", "*-dev.jar", "*-dev-shadow.jar", "*-all.jar")
            }

            def files = tree.files.toList()
            if (files.isEmpty()) throw new GradleException("No publishable jar found in: ${dir} (expected *-${ver}.jar)")
            if (files.size() > 1) throw new GradleException("Multiple *-${ver}.jar found in ${dir}: ${files*.name}")

            return files[0]
        }
    }

    static def collectTaskNameForPublishTask = { String publishTaskName ->
        // publishCurseforge_1_21_1_neoforge -> collect_1_21_1_neoforge
        if (publishTaskName.startsWith("publishCurseforge_")) {
            return publishTaskName.replaceFirst("publishCurseforge_", "collect_")
        }
        // publishModrinth_1_21_1_neoforge -> collect_1_21_1_neoforge
        if (publishTaskName.startsWith("publishModrinth_")) {
            return publishTaskName.replaceFirst("publishModrinth_", "collect_")
        }
        return null
    }

}
