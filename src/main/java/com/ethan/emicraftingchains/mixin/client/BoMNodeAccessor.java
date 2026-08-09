package com.ethan.emicraftingchains.mixin.client;

import dev.emi.emi.bom.MaterialNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "dev.emi.emi.screen.BoMScreen$Node", remap = false)
public interface BoMNodeAccessor {
    @Accessor("x")
    int emiCraftingChains$getX();

    @Accessor("y")
    int emiCraftingChains$getY();

    @Accessor("width")
    int emiCraftingChains$getWidth();

    @Accessor("node")
    MaterialNode emiCraftingChains$getNode();
}
