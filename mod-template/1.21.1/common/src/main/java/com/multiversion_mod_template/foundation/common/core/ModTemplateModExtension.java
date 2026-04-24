package com.multiversion_mod_template.foundation.common.core;

import com.github.hoshinofw.multiversion.DeleteMethodsAndFields;
import com.github.hoshinofw.multiversion.ModifyClass;
import com.github.hoshinofw.multiversion.OverwriteVersion;

@ModifyClass(ModTemplateMod.class)
@DeleteMethodsAndFields({"TBDeleted"})
public class ModTemplateModExtension {
    
    @OverwriteVersion
    public static String boo(Long i) {
        return "boo2";
    }



}
