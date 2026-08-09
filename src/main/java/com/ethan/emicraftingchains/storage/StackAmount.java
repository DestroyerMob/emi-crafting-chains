package com.ethan.emicraftingchains.storage;

import net.minecraft.world.item.ItemStack;

public record StackAmount(ItemStack stack, long amount) {
    public StackAmount {
        stack = stack.copy();
        stack.setCount(1);
    }
}
