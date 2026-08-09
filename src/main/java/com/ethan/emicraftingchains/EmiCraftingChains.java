package com.ethan.emicraftingchains;

import com.ethan.emicraftingchains.client.ClientBootstrap;
import com.ethan.emicraftingchains.compat.ae2.Ae2MultiblockCraftManager;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternDecoder;
import com.ethan.emicraftingchains.config.AutoCraftConfig;
import com.ethan.emicraftingchains.crafting.CraftJobManager;
import com.ethan.emicraftingchains.network.CraftNetwork;
import com.ethan.emicraftingchains.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(EmiCraftingChains.MOD_ID)
public final class EmiCraftingChains {
    public static final String MOD_ID = "multiblock_patterns";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EmiCraftingChains() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.register(modEventBus);
        modEventBus.addListener(this::onCommonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, AutoCraftConfig.SPEC);
        CraftNetwork.register();
        MinecraftForge.EVENT_BUS.register(CraftJobManager.class);
        MinecraftForge.EVENT_BUS.register(Ae2MultiblockCraftManager.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::register);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> appeng.api.crafting.PatternDetailsHelper.registerDecoder(
                new MultiblockPatternDecoder()));
    }
}
