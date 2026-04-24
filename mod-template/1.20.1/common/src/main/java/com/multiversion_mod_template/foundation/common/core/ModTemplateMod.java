package com.multiversion_mod_template.foundation.common.core;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModTemplateMod {

    public static final @NotNull String MOD_ID = "modtemplate";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initialized: version=1.20.1");

        //Common init code would go here.
        //Registry initialization/subscription...

    }

    public static void foo(int i) {

    }

    public static String boo(Long i) {
        return "boo";
    }

    public void TBDeleted() {
        
    }


}
