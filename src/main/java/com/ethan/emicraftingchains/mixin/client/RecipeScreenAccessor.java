package com.ethan.emicraftingchains.mixin.client;

import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = RecipeScreen.class, remap = false)
public interface RecipeScreenAccessor {
    @Accessor("currentPage")
    List<WidgetGroup> emiCraftingChains$getCurrentPage();
}
