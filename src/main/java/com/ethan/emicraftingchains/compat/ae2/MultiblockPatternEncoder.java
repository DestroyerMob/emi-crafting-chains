package com.ethan.emicraftingchains.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.core.definitions.AEItems;
import appeng.menu.SlotSemantics;
import appeng.menu.me.items.PatternEncodingTermMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Server-side equivalent of AE2's ordinary blank-pattern consumption. */
public final class MultiblockPatternEncoder {
    private MultiblockPatternEncoder() {
    }

    public static void encode(ServerPlayer player, MultiblockPatternData data) {
        if (!(player.containerMenu instanceof PatternEncodingTermMenu menu)) {
            return;
        }
        List<Slot> blankSlots = menu.getSlots(SlotSemantics.BLANK_PATTERN);
        List<Slot> encodedSlots = menu.getSlots(SlotSemantics.ENCODED_PATTERN);
        if (blankSlots.isEmpty() || encodedSlots.isEmpty()) {
            return;
        }
        Slot blankSlot = blankSlots.get(0);
        Slot encodedSlot = encodedSlots.get(0);
        ItemStack current = encodedSlot.getItem();
        if (!current.isEmpty()
                && !AEItems.BLANK_PATTERN.isSameAs(current)
                && !PatternDetailsHelper.isEncodedPattern(current)) {
            return;
        }
        if (current.isEmpty()) {
            ItemStack blanks = blankSlot.getItem();
            if (!AEItems.BLANK_PATTERN.isSameAs(blanks)) {
                player.displayClientMessage(Component.translatable(
                        "message.emi_crafting_chains.pattern_blank_required"), true);
                return;
            }
            blanks.shrink(1);
            if (blanks.isEmpty()) {
                blankSlot.set(ItemStack.EMPTY);
            }
            blankSlot.setChanged();
        }
        encodedSlot.set(MultiblockPatternItem.create(data));
        encodedSlot.setChanged();
        menu.broadcastChanges();
        player.displayClientMessage(Component.translatable(
                "message.emi_crafting_chains.pattern_encoded", data.root().getHoverName()), true);
    }
}
