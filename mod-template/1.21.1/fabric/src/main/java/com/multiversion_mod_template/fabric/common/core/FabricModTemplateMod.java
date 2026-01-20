package com.multiversion_mod_template.fabric.common.core;

import com.multiversion_mod_template.foundation.common.core.ModTemplateMod;
import com.multiversion_mod_template.foundation.common.core.SuperCoolClass;
import net.fabricmc.api.ModInitializer;

public class FabricModTemplateMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ModTemplateMod.init();
        Class<?> temp = SuperCoolClass.class;
    }
}
