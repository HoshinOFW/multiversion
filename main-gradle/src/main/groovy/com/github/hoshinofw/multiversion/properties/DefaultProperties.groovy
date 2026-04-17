package com.github.hoshinofw.multiversion.properties

import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension

class DefaultProperties {

    static final Map<String, String> defaultProperties = [
            "fabric_loader_version" : "0.19.2",
            "mixin_extras_version" : "0.3.5",
    ]

    static void assignIfNeeded(Project p) {
        ExtraPropertiesExtension ext = p.extensions.extraProperties
        defaultProperties.forEach {k, v ->
            if (p.findProperty(k) == null)  {
                ext.set(k, v)
            }
        }
    }
}
