package com.ethan.emicraftingchains.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.menu.me.common.MEStorageMenu;
import com.ethan.emicraftingchains.storage.ItemSource;
import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class Ae2ItemSource implements ItemSource {
    private final MEStorage storage;
    private final IActionSource actionSource;
    private final IEnergySource energySource;

    private Ae2ItemSource(MEStorageMenu menu) {
        this.storage = menu.getHost().getInventory();
        this.actionSource = menu.getActionSource();
        IGridNode node = menu.getNetworkNode();
        this.energySource = node == null || node.getGrid() == null
                ? null
                : node.getGrid().getEnergyService();
    }

    public static ItemSource create(ServerPlayer player) {
        return player.containerMenu instanceof MEStorageMenu menu ? new Ae2ItemSource(menu) : null;
    }

    @Override
    public List<StackAmount> snapshot() {
        KeyCounter available = storage.getAvailableStacks();
        List<StackAmount> result = new ArrayList<>();
        for (var entry : available) {
            AEKey key = entry.getKey();
            if (key instanceof AEItemKey itemKey && entry.getLongValue() > 0) {
                result.add(new StackAmount(itemKey.toStack(), entry.getLongValue()));
            }
        }
        return result;
    }

    @Override
    public long extract(ItemStack template, long amount, boolean simulate) {
        AEItemKey key = AEItemKey.of(template);
        if (key == null || amount <= 0) {
            return 0;
        }
        Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        if (energySource != null) {
            return StorageHelper.poweredExtraction(energySource, storage, key, amount, actionSource, mode);
        }
        return storage.extract(key, amount, mode, actionSource);
    }

    @Override
    public long insert(ItemStack template, long amount, boolean simulate) {
        AEItemKey key = AEItemKey.of(template);
        if (key == null || amount <= 0) {
            return 0;
        }
        Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        if (energySource != null) {
            return StorageHelper.poweredInsert(energySource, storage, key, amount, actionSource, mode);
        }
        return storage.insert(key, amount, mode, actionSource);
    }

    @Override
    public boolean isExternalStorage() {
        return true;
    }
}
