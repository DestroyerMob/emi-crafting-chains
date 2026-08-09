package com.ethan.emicraftingchains.mixin.client;

import dev.emi.emi.screen.BoMScreen;
import dev.emi.emi.api.widget.Bounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = BoMScreen.class, remap = false)
public interface BoMScreenAccessor {
    @Accessor("offX")
    double emiCraftingChains$getOffX();

    @Accessor("offY")
    double emiCraftingChains$getOffY();

    @Accessor("nodes")
    List<Object> emiCraftingChains$getNodes();

    @Accessor("batches")
    Bounds emiCraftingChains$getBatches();
}
