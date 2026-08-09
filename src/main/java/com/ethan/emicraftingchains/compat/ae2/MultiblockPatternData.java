package com.ethan.emicraftingchains.compat.ae2;

import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The pruned material set saved into one reusable AE2 multiblock pattern. */
public record MultiblockPatternData(
        ItemStack root,
        ResourceLocation recipeId,
        List<StackAmount> materials
) {
    public MultiblockPatternData {
        root = root.copy();
        if (!root.isEmpty()) {
            root.setCount(1);
        }
        materials = materials.stream()
                .map(entry -> {
                    ItemStack stack = entry.stack().copy();
                    stack.setCount(1);
                    return new StackAmount(stack, entry.amount());
                })
                .toList();
    }
}
