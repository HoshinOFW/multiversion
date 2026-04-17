package com.multiversion_mod_template.foundation.common.core;

import com.github.hoshinofw.multiversion.DeleteMethodsAndFields;
import com.github.hoshinofw.multiversion.ModifySignature;
import com.github.hoshinofw.multiversion.OverwriteVersion;
import com.github.hoshinofw.multiversion.ShadowVersion;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

@DeleteMethodsAndFields({"TBDeleted"})
public abstract class ModTemplateMod {

//    @ShadowVersion
//    @NotNull
//    public static final String MOD_ID;
//
//    @ShadowVersion
//    public static final Logger LOGGER;
    
    @ShadowVersion
    @ModifySignature("init")
    public static void init2();
    
    @ShadowVersion
    @ModifySignature("foo")
    public static void foo(boolean b);

}
