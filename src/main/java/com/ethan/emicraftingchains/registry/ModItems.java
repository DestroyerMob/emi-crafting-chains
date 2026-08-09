package com.ethan.emicraftingchains.registry;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(
            ForgeRegistries.ITEMS, EmiCraftingChains.MOD_ID);

    public static final RegistryObject<Item> MULTIBLOCK_PATTERN = ITEMS.register(
            "multiblock_pattern",
            () -> new MultiblockPatternItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
