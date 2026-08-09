package com.ethan.emicraftingchains;

import com.ethan.emicraftingchains.client.ClientBootstrap;
import com.ethan.emicraftingchains.crafting.CraftJobManager;
import com.ethan.emicraftingchains.network.CraftNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(EmiCraftingChains.MOD_ID)
public final class EmiCraftingChains {
    public static final String MOD_ID = "emi_crafting_chains";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EmiCraftingChains() {
        CraftNetwork.register();
        MinecraftForge.EVENT_BUS.register(CraftJobManager.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::register);
    }
}
