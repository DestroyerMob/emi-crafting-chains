package com.ethan.emicraftingchains.compat.ae2;

import appeng.core.definitions.AEItems;
import com.ethan.emicraftingchains.registry.ModItems;
import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** A reusable, non-provider AE2 pattern containing a pruned multiblock bill of materials. */
public final class MultiblockPatternItem extends Item {
    private static final int MAX_STORED_MATERIALS = 512;
    private static final String ROOT_TAG = "Structure";
    private static final String RECIPE_TAG = "Recipe";
    private static final String MATERIALS_TAG = "Materials";
    private static final String STACK_TAG = "Stack";
    private static final String AMOUNT_TAG = "Amount";

    public MultiblockPatternItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(MultiblockPatternData data) {
        ItemStack pattern = new ItemStack(ModItems.MULTIBLOCK_PATTERN.get());
        CompoundTag tag = pattern.getOrCreateTag();
        tag.put(ROOT_TAG, data.root().save(new CompoundTag()));
        if (data.recipeId() != null) {
            tag.putString(RECIPE_TAG, data.recipeId().toString());
        }
        ListTag materials = new ListTag();
        for (StackAmount material : data.materials()) {
            CompoundTag entry = new CompoundTag();
            entry.put(STACK_TAG, material.stack().save(new CompoundTag()));
            entry.putLong(AMOUNT_TAG, material.amount());
            materials.add(entry);
        }
        tag.put(MATERIALS_TAG, materials);
        return pattern;
    }

    public static boolean isMultiblockPattern(ItemStack stack) {
        return stack.is(ModItems.MULTIBLOCK_PATTERN.get()) && !readMaterials(stack).isEmpty();
    }

    public static ItemStack readRoot(ItemStack pattern) {
        CompoundTag tag = pattern.getTag();
        if (tag == null || !tag.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.of(tag.getCompound(ROOT_TAG));
    }

    public static ResourceLocation readRecipeId(ItemStack pattern) {
        CompoundTag tag = pattern.getTag();
        return tag == null ? null : ResourceLocation.tryParse(tag.getString(RECIPE_TAG));
    }

    public static List<StackAmount> readMaterials(ItemStack pattern) {
        CompoundTag tag = pattern.getTag();
        if (tag == null || !tag.contains(MATERIALS_TAG, Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag stored = tag.getList(MATERIALS_TAG, Tag.TAG_COMPOUND);
        int size = Math.min(stored.size(), MAX_STORED_MATERIALS);
        List<StackAmount> materials = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            CompoundTag entry = stored.getCompound(i);
            ItemStack stack = ItemStack.of(entry.getCompound(STACK_TAG));
            long amount = entry.getLong(AMOUNT_TAG);
            if (!stack.isEmpty() && amount > 0 && amount <= 1_000_000L) {
                stack.setCount(1);
                materials.add(new StackAmount(stack, amount));
            }
        }
        return List.copyOf(materials);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            return InteractionResultHolder.sidedSuccess(
                    AEItems.BLANK_PATTERN.stack(held.getCount()), level.isClientSide());
        }
        return InteractionResultHolder.pass(held);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        ItemStack root = readRoot(stack);
        List<StackAmount> materials = readMaterials(stack);
        if (!root.isEmpty()) {
            tooltip.add(Component.translatable(
                    "tooltip.emi_crafting_chains.pattern_structure", root.getHoverName())
                    .withStyle(ChatFormatting.AQUA));
        }
        long total = materials.stream().mapToLong(StackAmount::amount).sum();
        tooltip.add(Component.translatable(
                "tooltip.emi_crafting_chains.pattern_materials", materials.size(), total)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.emi_crafting_chains.pattern_request")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.emi_crafting_chains.pattern_provider_safe")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
