package com.ethan.emicraftingchains.crafting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One root in an atomic craft request. Normal roots always craft new recipe
 * batches. Material roots may consume an existing matching stack before
 * recursively crafting a missing one, which is what structure recipes need.
 */
public record CraftTarget(
        ItemStack stack,
        ResourceLocation preferredRecipeId,
        List<ResourceLocation> chainRecipeIds,
        List<ItemStack> suppliedOnly,
        int amount,
        boolean useExisting
) {
    public CraftTarget {
        stack = stack.copy();
        if (!stack.isEmpty()) {
            stack.setCount(1);
        }
        chainRecipeIds = List.copyOf(chainRecipeIds);
        suppliedOnly = suppliedOnly.stream().map(entry -> {
            ItemStack copy = entry.copy();
            copy.setCount(1);
            return copy;
        }).toList();
    }

    public static CraftTarget recipe(
            ItemStack output,
            ResourceLocation preferredRecipeId,
            List<ResourceLocation> chainRecipeIds,
            int batches
    ) {
        return recipe(output, preferredRecipeId, chainRecipeIds, List.of(), batches);
    }

    public static CraftTarget recipe(
            ItemStack output,
            ResourceLocation preferredRecipeId,
            List<ResourceLocation> chainRecipeIds,
            List<ItemStack> suppliedOnly,
            int batches
    ) {
        return new CraftTarget(output, preferredRecipeId, chainRecipeIds, suppliedOnly, batches, false);
    }

    public static CraftTarget material(
            ItemStack stack,
            List<ResourceLocation> chainRecipeIds,
            int count
    ) {
        return material(stack, chainRecipeIds, List.of(), count);
    }

    public static CraftTarget material(
            ItemStack stack,
            List<ResourceLocation> chainRecipeIds,
            List<ItemStack> suppliedOnly,
            int count
    ) {
        return new CraftTarget(stack, null, chainRecipeIds, suppliedOnly, count, true);
    }
}
