package com.ethan.emicraftingchains.client;

import net.minecraftforge.common.MinecraftForge;

public final class ClientBootstrap {
    private ClientBootstrap() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(ChainCraftHandler.class);
        MinecraftForge.EVENT_BUS.register(CraftingGridAnimator.class);
    }
}
