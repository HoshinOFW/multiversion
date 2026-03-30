package com.github.hoshinofw.multiversion.loom

import com.github.hoshinofw.multiversion.util.GeneralUtil
import org.gradle.api.Project

class SubprojectLoomConfiguration {

    static void configure(Project p) {

        if (!GeneralUtil.isCommon(p) && GeneralUtil.isNotBaseVersionModule(p)) {
            // set before applying loom
            p.extensions.extraProperties.set("loom.platform", GeneralUtil.loaderTypeOf(p))
            p.pluginManager.apply("dev.architectury.loom")
        }

        p.loom {
            if (!GeneralUtil.isMcVersion(p, "1.20.1")) {
                it.silentMojangMappingsLicense()
            }
            if (GeneralUtil.isForge(p)) {
                it.forge { forge ->
                    forge.mixinConfig "${p.findProperty("mod_id")}.mixins.json"
                }
            }
        }
    }

}
