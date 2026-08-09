package com.ethan.emicraftingchains.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

    /**
     * Identity used only for availability matching and visual batching. A
     * reusable crafting tool remains the same logical tool while damage,
     * charge, and GTCEu's crafting-sound timestamp change between uses.
     * Transaction requirements continue to use {@link #of(ItemStack)}.
     */
    public static StackKey forBatching(ItemStack stack) {
        CompoundTag serialized = stack.save(new CompoundTag());
        if (isReusableCraftingTool(stack) && serialized.contains("tag", Tag.TAG_COMPOUND)) {
            CompoundTag itemTag = serialized.getCompound("tag");
            itemTag.remove("Damage");
            if (itemTag.contains("GT.Tool", Tag.TAG_COMPOUND)) {
                CompoundTag toolTag = itemTag.getCompound("GT.Tool");
                toolTag.remove("Damage");
                toolTag.remove("Charge");
                toolTag.remove("LastCraftingUse");
            }
        }
        return new StackKey(serialized);
    }

    public static boolean isReusableCraftingTool(ItemStack stack) {
        return !stack.isEmpty()
                && stack.isDamageableItem()
                && stack.getItem().hasCraftingRemainingItem(stack);
    }

    public static boolean sameForBatching(ItemStack first, ItemStack second) {
        return !first.isEmpty()
                && !second.isEmpty()
                && first.getItem() == second.getItem()
                && forBatching(first).equals(forBatching(second));
    }

    public ItemStack toStack() {
        CompoundTag tag = serialized.copy();
        tag.putByte("Count", (byte) 1);
        return ItemStack.of(tag);
    }
}
