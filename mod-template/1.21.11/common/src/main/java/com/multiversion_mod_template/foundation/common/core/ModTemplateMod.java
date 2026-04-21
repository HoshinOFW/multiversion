package com.multiversion_mod_template.foundation.common.core;


import com.github.hoshinofw.multiversion.OverwriteVersion;
import com.github.hoshinofw.multiversion.ShadowVersion;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public abstract class ModTemplateMod {


    @ShadowVersion
    public static final String MOD_ID;
 
    @ShadowVersion
    public static final Logger LOGGER;

    @OverwriteVersion
    public static void init2() {
        LOGGER.info("Initialized: version=1.21.11");
    }

}
