package com.multiversion_mod_template.foundation.common.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModTemplateMod {
    public static final String MOD_ID = "modtemplate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initialized: version=1.21.1");
        int i = SuperCoolClass.i;
        //Common init code would go here.
        //Registry initialization/subscription...

    }

}
