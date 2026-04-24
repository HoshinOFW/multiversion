package com.multiversion_mod_template.foundation.common.core;

import com.github.hoshinofw.multiversion.ModifyClass;
import com.github.hoshinofw.multiversion.OverwriteVersion;

@ModifyClass(SuperCoolClass.class)
public class SuperCoolClass {

    @OverwriteVersion
    public static final int i = 1;

}
