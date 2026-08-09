package com.ethan.emicraftingchains.client;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternData;
import com.ethan.emicraftingchains.network.CraftNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;

import java.util.Arrays;

/** Bridges an EMI multiblock selection into AE2's existing Encode action. */
public final class MultiblockPatternEncodingClient {
    private static ResourceLocation armedRecipe;

    private MultiblockPatternEncodingClient() {
    }

    public static void arm(ResourceLocation recipeId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (recipeId != null && minecraft.player != null
                && minecraft.player.containerMenu instanceof PatternEncodingTermMenu) {
            armedRecipe = recipeId;
        } else {
            armedRecipe = null;
        }
    }

    public static void reset() {
        armedRecipe = null;
    }

    public static boolean tryEncode(PatternEncodingTermMenu menu) {
        if (armedRecipe == null || !menu.isClientSide()) {
            return false;
        }
        if (hasNormalPatternContents(menu)) {
            armedRecipe = null;
            return false;
        }
        MultiblockPatternData data = ChainCraftHandler.createCurrentMultiblockPattern();
        if (data == null || !armedRecipe.equals(data.recipeId())) {
            armedRecipe = null;
            return false;
        }
        CraftNetwork.sendMultiblockPatternEncode(data);
        armedRecipe = null;
        return true;
    }

    private static boolean hasNormalPatternContents(PatternEncodingTermMenu menu) {
        return hasStack(menu.getCraftingGridSlots())
                || hasStack(menu.getProcessingInputSlots())
                || hasStack(menu.getProcessingOutputSlots())
                || hasStack(menu.getSmithingTableTemplateSlot())
                || hasStack(menu.getSmithingTableBaseSlot())
                || hasStack(menu.getSmithingTableAdditionSlot());
    }

    private static boolean hasStack(Slot... slots) {
        return Arrays.stream(slots).anyMatch(slot -> slot != null && slot.hasItem());
    }
}
