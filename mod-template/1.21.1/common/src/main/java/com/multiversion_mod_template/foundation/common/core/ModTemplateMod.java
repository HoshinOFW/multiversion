package com.multiversion_mod_template.foundation.common.core;

import com.github.hoshinofw.multiversion.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

@ModifyClass
@DeleteMethodsAndFields({"TBDeleted"})
public abstract class ModTemplateMod {

    @ShadowVersion
    @NotNull
    public static final String MOD_ID;  

    @ShadowVersion
    public static final Logger LOGGER;
    
    @ShadowVersion
    @ModifySignature("init")
    public static void init2();
    
    @ShadowVersion
    @ModifySignature("foo")
    public static void foo(boolean b);

    int bro;

}
