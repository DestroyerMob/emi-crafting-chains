package com.ethan.emicraftingchains.client;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.network.CraftNetwork;
import com.ethan.emicraftingchains.storage.StackAmount;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ChainCraftHandler {
    private static final int BUTTON_WIDTH = 150;
    private static final long AVAILABILITY_REFRESH_MILLIS = 5_000L;
    private static final Map<BoMScreen, Button> BUTTONS = new WeakHashMap<>();

    private static EmiPlayerInventory syncedInventory;
    private static String lastRequestSignature;
    private static long lastRequestMillis;
    private static int nextRequestId;
    private static int latestRequestId;
    private static int maximumBatches = -1;

    private ChainCraftHandler() {
    }

    @SubscribeEvent
    public static void onScreenInitialized(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BoMScreen screen)) {
            return;
        }

        syncedInventory = null;
        lastRequestSignature = null;
        lastRequestMillis = 0L;
        maximumBatches = -1;
        BoM.craftingMode = true;
        screen.recalculateTree();

        Button button = Button.builder(
                        Component.translatable("button.emi_crafting_chains.craft_chain_checking"),
                        ignored -> craftTree(screen))
                .bounds((screen.width - BUTTON_WIDTH) / 2, screen.height - 27, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.emi_crafting_chains.craft_chain")))
                .build();
        BUTTONS.put(screen, button);
        event.addListener(button);
        requestAvailability(screen, true);
    }

    /** EMI's tree screen does not call Screen.render, so its vanilla children
     * must be rendered explicitly. */
    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof BoMScreen screen)) {
            return;
        }

        // EMI's native crafting mode renders an insufficient resource as a
        // red available/required amount. The inventory redirect makes those
        // values include the currently open Tom's or AE2 terminal.
        BoM.craftingMode = true;
        requestAvailability(screen, false);

        Button button = BUTTONS.get(screen);
        if (button != null) {
            button.active = hasCraftableTree();
            button.setMessage(buttonMessage());
            button.render(
                    event.getGuiGraphics(),
                    event.getMouseX(),
                    event.getMouseY(),
                    event.getPartialTick()
            );
        }
    }

    /** Handle the control before EMI interprets the same coordinates as a
     * recipe-tree node or drag operation. */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof BoMScreen screen)) {
            return;
        }
        Button button = BUTTONS.get(screen);
        if (button != null && button.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    /** Called by the EMI mixin while it rebuilds its resource cost widgets. */
    public static EmiPlayerInventory getSyncedInventory(Player player) {
        return syncedInventory == null ? EmiPlayerInventory.of(player) : syncedInventory;
    }

    /** Applies an authoritative storage response on the client thread. */
    public static void acceptAvailability(
            int requestId,
            int serverMaximumBatches,
            List<StackAmount> available
    ) {
        if (requestId != latestRequestId) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof BoMScreen screen)) {
            return;
        }

        List<EmiStack> stacks = new ArrayList<>(available.size());
        for (StackAmount entry : available) {
            if (!entry.stack().isEmpty() && entry.amount() > 0) {
                stacks.add(EmiStack.of(entry.stack(), entry.amount()));
            }
        }
        syncedInventory = new EmiPlayerInventory(stacks);
        maximumBatches = serverMaximumBatches;
        BoM.craftingMode = true;
        screen.recalculateTree();
    }

    private static Component buttonMessage() {
        if (maximumBatches < 0) {
            return Component.translatable("button.emi_crafting_chains.craft_chain_checking");
        }
        Component message = Component.translatable(
                "button.emi_crafting_chains.craft_chain_max",
                maximumBatches
        );
        MaterialTree tree = BoM.tree;
        if (tree != null && tree.batches > maximumBatches) {
            return message.copy().withStyle(ChatFormatting.RED);
        }
        return message;
    }

    private static boolean hasCraftableTree() {
        MaterialTree tree = BoM.tree;
        return tree != null && tree.goal != null && tree.goal.recipe != null;
    }

    private static void requestAvailability(BoMScreen screen, boolean force) {
        TreeRequest request = createTreeRequest();
        if (request == null) {
            return;
        }

        long now = Util.getMillis();
        boolean changed = !request.signature().equals(lastRequestSignature);
        if (!force && !changed && now - lastRequestMillis < AVAILABILITY_REFRESH_MILLIS) {
            return;
        }

        if (changed && syncedInventory != null) {
            syncedInventory = null;
            maximumBatches = -1;
            BoM.craftingMode = true;
            screen.recalculateTree();
        }

        int requestId = ++nextRequestId;
        latestRequestId = requestId;
        lastRequestSignature = request.signature();
        lastRequestMillis = now;
        CraftNetwork.requestAvailability(
                requestId,
                request.output().getItem(),
                request.rootRecipeId(),
                request.recipeIds(),
                request.batches(),
                request.queriedStacks()
        );
    }

    private static TreeRequest createTreeRequest() {
        MaterialTree tree = BoM.tree;
        if (tree == null || tree.goal == null || tree.goal.recipe == null) {
            return null;
        }

        EmiRecipe rootRecipe = tree.goal.recipe;
        ResourceLocation rootRecipeId = rootRecipe.getId();
        EmiStack rootOutput = rootRecipe.getOutputs().stream()
                .filter(stack -> !stack.getItemStack().isEmpty())
                .findFirst()
                .orElse(null);
        if (rootRecipeId == null || rootOutput == null) {
            return null;
        }

        Set<ResourceLocation> recipes = new LinkedHashSet<>();
        collectRecipes(tree.goal, recipes);
        List<ItemStack> queriedStacks = new ArrayList<>();
        // Do not count an already-owned copy of the requested result as a
        // craftable batch. Existing intermediate products should count, so
        // query every child node but deliberately skip the goal itself.
        if (tree.goal.children != null) {
            for (MaterialNode child : tree.goal.children) {
                collectQueriedStacks(child, queriedStacks);
            }
        }
        int batches = (int) Math.max(1L, Math.min(tree.batches, CraftNetwork.MAX_CHAIN_BATCHES));
        List<ResourceLocation> recipeIds = recipes.stream().toList();
        String signature = rootRecipeId + "|" + batches + "|" + recipeIds;
        return new TreeRequest(
                rootOutput.getItemStack(),
                rootRecipeId,
                recipeIds,
                batches,
                List.copyOf(queriedStacks),
                signature
        );
    }

    private static void craftTree(BoMScreen screen) {
        TreeRequest request = createTreeRequest();
        if (request == null) {
            return;
        }

        EmiCraftingChains.LOGGER.info(
                "Submitting EMI craft chain: item={}, root={}, recipes={}, batches={}",
                request.output().getItem(),
                request.rootRecipeId(),
                request.recipeIds().size(),
                request.batches()
        );
        CraftNetwork.sendChainRequest(
                request.output().getItem(),
                request.rootRecipeId(),
                request.recipeIds(),
                request.batches()
        );

        Minecraft minecraft = Minecraft.getInstance();
        if (screen.old != null) {
            minecraft.setScreen(screen.old);
        }
    }

    private static void collectRecipes(MaterialNode node, Set<ResourceLocation> recipes) {
        if (node == null || recipes.size() >= CraftNetwork.MAX_CHAIN_RECIPES) {
            return;
        }
        if (node.recipe != null && node.recipe.getId() != null) {
            recipes.add(node.recipe.getId());
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectRecipes(child, recipes);
            }
        }
    }

    private static void collectQueriedStacks(MaterialNode node, List<ItemStack> queriedStacks) {
        if (node == null || queriedStacks.size() >= CraftNetwork.MAX_AVAILABILITY_STACKS) {
            return;
        }
        if (node.ingredient != null) {
            for (EmiStack emiStack : node.ingredient.getEmiStacks()) {
                ItemStack stack = emiStack.getItemStack();
                if (!stack.isEmpty()) {
                    stack = stack.copy();
                    stack.setCount(1);
                    addUniqueStack(queriedStacks, stack);
                    if (queriedStacks.size() >= CraftNetwork.MAX_AVAILABILITY_STACKS) {
                        return;
                    }
                }
            }
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectQueriedStacks(child, queriedStacks);
                if (queriedStacks.size() >= CraftNetwork.MAX_AVAILABILITY_STACKS) {
                    return;
                }
            }
        }
    }

    private static void addUniqueStack(List<ItemStack> stacks, ItemStack candidate) {
        for (ItemStack existing : stacks) {
            if (ItemStack.isSameItemSameTags(existing, candidate)) {
                return;
            }
        }
        stacks.add(candidate);
    }

    private record TreeRequest(
            ItemStack output,
            ResourceLocation rootRecipeId,
            List<ResourceLocation> recipeIds,
            int batches,
            List<ItemStack> queriedStacks,
            String signature
    ) {
    }
}
