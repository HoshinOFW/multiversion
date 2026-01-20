package com.multiversion_mod_template.fabric.common.core;

import net.fabricmc.api.ClientModInitializer;
import com.multiversion_mod_template.foundation.common.core.ClientModTemplateMod;

public class FabricClientModTemplateMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientModTemplateMod.init();
    }
}
