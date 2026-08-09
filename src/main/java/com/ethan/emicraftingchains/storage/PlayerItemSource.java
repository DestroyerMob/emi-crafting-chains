package com.ethan.emicraftingchains.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class PlayerItemSource implements ItemSource {
    private final ServerPlayer player;

    public PlayerItemSource(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public List<StackAmount> snapshot() {
        List<StackAmount> result = new ArrayList<>();
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) {
                result.add(new StackAmount(stack, stack.getCount()));
            }
        }
        return result;
    }

    @Override
    public long extract(ItemStack template, long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long remaining = amount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (!ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }
            int taken = (int) Math.min(remaining, stack.getCount());
            if (!simulate) {
                stack.shrink(taken);
                if (stack.isEmpty()) {
                    inventory.items.set(slot, ItemStack.EMPTY);
                }
            }
            remaining -= taken;
        }
        if (!simulate && remaining != amount) {
            inventory.setChanged();
        }
        return amount - remaining;
    }

    @Override
    public long insert(ItemStack template, long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        if (simulate) {
            return simulatedSpace(template, amount);
        }

        long remaining = amount;
        while (remaining > 0) {
            ItemStack part = template.copy();
            part.setCount((int) Math.min(remaining, part.getMaxStackSize()));
            int offered = part.getCount();
            player.getInventory().add(part);
            int accepted = offered - part.getCount();
            remaining -= accepted;
            if (accepted == 0) {
                break;
            }
        }
        player.getInventory().setChanged();
        return amount - remaining;
    }

    private long simulatedSpace(ItemStack template, long limit) {
        long space = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                space += template.getMaxStackSize();
            } else if (ItemStack.isSameItemSameTags(stack, template)) {
                space += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
            if (space >= limit) {
                return limit;
            }
        }
        return Math.min(space, limit);
    }
}
