package com.ethan.emicraftingchains.compat.toms;

import com.ethan.emicraftingchains.storage.ItemSource;
import com.ethan.emicraftingchains.storage.StackAmount;
import com.tom.storagemod.gui.StorageTerminalMenu;
import com.tom.storagemod.tile.StorageTerminalBlockEntity;
import com.tom.storagemod.util.StoredItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TomsItemSource implements ItemSource {
    private static final Field TERMINAL_FIELD = findTerminalField();

    private final StorageTerminalBlockEntity terminal;

    private TomsItemSource(StorageTerminalBlockEntity terminal) {
        this.terminal = terminal;
    }

    public static ItemSource create(ServerPlayer player) {
        if (!(player.containerMenu instanceof StorageTerminalMenu menu) || TERMINAL_FIELD == null) {
            return null;
        }
        try {
            Object terminal = TERMINAL_FIELD.get(menu);
            return terminal instanceof StorageTerminalBlockEntity storage ? new TomsItemSource(storage) : null;
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    @Override
    public List<StackAmount> snapshot() {
        // getStacks marks the aggregate for refresh; updateServer performs that refresh now.
        terminal.getStacks();
        terminal.updateServer();
        Map<StoredItemStack, Long> stacks = terminal.getStacks();
        List<StackAmount> result = new ArrayList<>(stacks.size());
        stacks.forEach((stack, amount) -> result.add(new StackAmount(stack.getStack(), amount)));
        return result;
    }

    @Override
    public long extract(ItemStack template, long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        if (simulate) {
            long available = snapshot().stream()
                    .filter(entry -> ItemStack.isSameItemSameTags(entry.stack(), template))
                    .mapToLong(StackAmount::amount)
                    .sum();
            return Math.min(amount, available);
        }
        StoredItemStack extracted = terminal.pullStack(new StoredItemStack(template, 1), amount);
        return extracted == null ? 0 : extracted.getQuantity();
    }

    @Override
    public long insert(ItemStack template, long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        if (simulate) {
            // Tom's public API has no non-mutating aggregate insertion operation.
            return 0;
        }

        long remaining = amount;
        while (remaining > 0) {
            ItemStack part = template.copy();
            part.setCount((int) Math.min(remaining, part.getMaxStackSize()));
            int offered = part.getCount();
            ItemStack remainder = terminal.pushStack(part);
            int accepted = offered - remainder.getCount();
            remaining -= accepted;
            if (accepted == 0) {
                break;
            }
        }
        return amount - remaining;
    }

    @Override
    public boolean isExternalStorage() {
        return true;
    }

    private static Field findTerminalField() {
        try {
            Field field = StorageTerminalMenu.class.getDeclaredField("te");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
