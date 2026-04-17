package com.multiversion_mod_template.foundation.common.core;


import com.github.hoshinofw.multiversion.ShadowVersion;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public abstract class ModTemplateMod {

    @ShadowVersion
    @NotNull
    public static final String MOD_ID;
 
    @ShadowVersion
    public static final Logger LOGGER;

    @ShadowVersion
    public static void init2();

}
