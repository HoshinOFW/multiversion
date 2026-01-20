package com.github.hoshinofw.multiversion.loom

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class PreLoomApplicationConfiguration {

    static void configure(Project p) {
        if (!GeneralUtil.isCommon(p) && GeneralUtil.isNotBaseVersionModule(p)) {
            p.extensions.extraProperties.set("loom.platform", GeneralUtil.getModLoader(p))
            p.pluginManager.apply("dev.architectury.loom")
        }
    }

}
