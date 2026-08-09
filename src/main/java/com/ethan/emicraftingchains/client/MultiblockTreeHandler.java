package com.ethan.emicraftingchains.client;

import com.ethan.emicraftingchains.mixin.client.RecipeScreenAccessor;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.screen.BoMScreen;
import dev.emi.emi.screen.RecipeScreen;
import dev.emi.emi.screen.WidgetGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.WeakHashMap;

/** Adds a structure-material tree action to EMI multiblock-info recipes. */
public final class MultiblockTreeHandler {
    private static final int BUTTON_WIDTH = 150;
    private static final Map<RecipeScreen, Button> BUTTONS = new WeakHashMap<>();

    private MultiblockTreeHandler() {
    }

    @SubscribeEvent
    public static void onScreenInitialized(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof RecipeScreen screen)) {
            return;
        }
        Button button = Button.builder(
                        Component.translatable("button.emi_crafting_chains.multiblock_tree"),
                        ignored -> openTree(screen))
                .bounds((screen.width - BUTTON_WIDTH) / 2, screen.height - 27, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "tooltip.emi_crafting_chains.multiblock_tree")))
                .build();
        BUTTONS.put(screen, button);
        event.addListener(button);
    }

    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof RecipeScreen screen)) {
            return;
        }
        Button button = BUTTONS.get(screen);
        if (button == null) {
            return;
        }
        button.visible = currentMultiblockRecipe(screen) != null;
        button.active = button.visible;
        if (button.visible) {
            button.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof RecipeScreen screen)) {
            return;
        }
        Button button = BUTTONS.get(screen);
        if (button != null && button.visible
                && button.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    private static void openTree(RecipeScreen screen) {
        EmiRecipe recipe = currentMultiblockRecipe(screen);
        if (recipe == null || screen.old == null) {
            return;
        }
        BoM.setGoal(recipe);
        MultiblockPatternEncodingClient.arm(recipe.getId());
        BoM.craftingMode = true;
        Minecraft.getInstance().setScreen(new BoMScreen(screen.old));
    }

    private static EmiRecipe currentMultiblockRecipe(RecipeScreen screen) {
        for (WidgetGroup group : ((RecipeScreenAccessor) screen).emiCraftingChains$getCurrentPage()) {
            if (ChainCraftHandler.isMultiblockRecipe(group.recipe)) {
                return group.recipe;
            }
        }
        return null;
    }
}
