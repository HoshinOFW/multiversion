package com.multiversion_mod_template.fabric.common.core;

import com.multiversion_mod_template.foundation.common.core.ClientModTemplateMod;
import net.fabricmc.api.ClientModInitializer;

public class FabricClientModTemplateMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientModTemplateMod.init();
    }
}
