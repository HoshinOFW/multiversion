package com.multiversion_mod_template.foundation.common.core;

import com.github.hoshinofw.multiversion.DeleteMethodsAndFields;
import com.github.hoshinofw.multiversion.ModifySignature;
import com.github.hoshinofw.multiversion.OverwriteVersion;
import com.github.hoshinofw.multiversion.ShadowVersion;
import org.slf4j.Logger;

@DeleteMethodsAndFields({"TBDeleted"})
public abstract class ModTemplateMod {
    @ShadowVersion
    public static final String MOD_ID;

    @ShadowVersion
    public static final Logger LOGGER;
 
    @OverwriteVersion
    @ModifySignature("init")
    public static void init() {
        LOGGER.info("Initialized: version=1.21.1");
        int i = SuperCoolClass.i;
        //Common init code would go here.
        //Registry initialization/subscription...
    }

}
