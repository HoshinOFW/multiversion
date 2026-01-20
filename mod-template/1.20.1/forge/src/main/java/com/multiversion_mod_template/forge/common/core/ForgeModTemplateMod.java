package com.multiversion_mod_template.forge.common.core;

import com.multiversion_mod_template.foundation.common.core.ModTemplateMod;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ModTemplateMod.MOD_ID)
public class ForgeModTemplateMod {
    public static IEventBus MOD_BUS;

    public ForgeModTemplateMod() {
        MOD_BUS = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(ModTemplateMod.MOD_ID, MOD_BUS);

        ModTemplateMod.init();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ForgeClientModTemplateMod::init);


    }

}
