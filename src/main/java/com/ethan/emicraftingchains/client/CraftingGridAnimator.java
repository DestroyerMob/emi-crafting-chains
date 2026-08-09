package com.ethan.emicraftingchains.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CraftingGridAnimator {
    private static final long INPUT_PHASE_MILLIS = 375L;
    private static final long STEP_MILLIS = 500L;

    private static List<ItemStack> inputs = List.of();
    private static ItemStack output = ItemStack.EMPTY;
    private static long startedAt;

    private CraftingGridAnimator() {
    }

    public static void show(List<ItemStack> ingredientStacks, ItemStack result) {
        inputs = ingredientStacks.stream().map(ItemStack::copy).toList();
        output = result.copy();
        startedAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        if (output.isEmpty() || elapsed < 0 || elapsed >= STEP_MILLIS) {
            return;
        }

        CraftingLayout layout = findCraftingLayout(screen);
        if (layout == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        if (elapsed < INPUT_PHASE_MILLIS) {
            for (int i = 0; i < Math.min(inputs.size(), layout.inputs.size()); i++) {
                ItemStack stack = inputs.get(i);
                if (!stack.isEmpty()) {
                    renderInSlot(graphics, minecraft, screen, layout.inputs.get(i), stack);
                }
            }
        } else {
            renderInSlot(graphics, minecraft, screen, layout.output, output);
        }
    }

    private static CraftingLayout findCraftingLayout(AbstractContainerScreen<?> screen) {
        String screenName = screen.getClass().getName().toLowerCase();
        String menuName = screen.getMenu().getClass().getName().toLowerCase();
        if (!screenName.contains("craft") && !menuName.contains("craft")) {
            return null;
        }

        List<Slot> containerSlots = screen.getMenu().slots.stream()
                .filter(slot -> !(slot.container instanceof Inventory))
                .toList();
        for (Slot topLeft : containerSlots) {
            List<Slot> grid = findGrid(containerSlots, topLeft.x, topLeft.y);
            if (grid == null) {
                continue;
            }

            Slot result = containerSlots.stream()
                    .filter(slot -> !grid.contains(slot))
                    .filter(slot -> slot.x - topLeft.x >= 54 && slot.x - topLeft.x <= 126)
                    .filter(slot -> Math.abs(slot.y - (topLeft.y + 18)) <= 18)
                    .min(Comparator.comparingInt(slot ->
                            Math.abs(slot.x - (topLeft.x + 94))
                                    + Math.abs(slot.y - (topLeft.y + 18))))
                    .orElse(null);
            if (result != null) {
                return new CraftingLayout(grid, result);
            }
        }
        return null;
    }

    private static List<Slot> findGrid(List<Slot> slots, int x, int y) {
        List<Slot> result = new ArrayList<>(9);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                int expectedX = x + column * 18;
                int expectedY = y + row * 18;
                Slot match = slots.stream()
                        .filter(slot -> slot.x == expectedX && slot.y == expectedY)
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    return null;
                }
                result.add(match);
            }
        }
        return result;
    }

    private static void renderInSlot(
            GuiGraphics graphics,
            Minecraft minecraft,
            AbstractContainerScreen<?> screen,
            Slot slot,
            ItemStack stack
    ) {
        int x = screen.getGuiLeft() + slot.x;
        int y = screen.getGuiTop() + slot.y;
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(minecraft.font, stack, x, y);
    }

    private record CraftingLayout(List<Slot> inputs, Slot output) {
    }
}
