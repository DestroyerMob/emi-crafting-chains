package com.ethan.emicraftingchains.storage;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** A server-side, exact-stack item store used by the atomic crafting commit. */
public interface ItemSource {
    List<StackAmount> snapshot();

    /** Returns the number of matching items extracted. */
    long extract(ItemStack template, long amount, boolean simulate);

    /** Returns the number of matching items accepted. */
    long insert(ItemStack template, long amount, boolean simulate);

    default boolean isExternalStorage() {
        return false;
    }
}
