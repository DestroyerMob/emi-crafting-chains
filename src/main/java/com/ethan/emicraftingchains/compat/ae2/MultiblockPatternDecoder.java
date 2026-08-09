package com.ethan.emicraftingchains.compat.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Marks our item as encoded so AE2's blank/encoded slots and Clear action work.
 * It intentionally returns no provider recipe: a normal processing pattern would
 * consume the requested blocks instead of leaving them in ME storage.
 */
public final class MultiblockPatternDecoder implements IPatternDetailsDecoder {
    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return MultiblockPatternItem.isMultiblockPattern(stack);
    }

    @Override
    public IPatternDetails decodePattern(AEItemKey key, Level level) {
        return null;
    }

    @Override
    public IPatternDetails decodePattern(ItemStack stack, Level level, boolean autoRecovery) {
        return null;
    }
}
