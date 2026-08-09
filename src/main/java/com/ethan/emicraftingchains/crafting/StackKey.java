package com.ethan.emicraftingchains.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Exact ItemStack identity, including NBT and Forge capability serialization. */
public record StackKey(CompoundTag serialized) {
    public StackKey {
        serialized = serialized.copy();
        serialized.remove("Count");
    }

    public static StackKey of(ItemStack stack) {
        return new StackKey(stack.save(new CompoundTag()));
    }

    public ItemStack toStack() {
        CompoundTag tag = serialized.copy();
        tag.putByte("Count", (byte) 1);
        return ItemStack.of(tag);
    }
}
