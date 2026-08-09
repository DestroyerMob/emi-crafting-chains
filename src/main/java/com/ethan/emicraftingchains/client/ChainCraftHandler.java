package com.ethan.emicraftingchains.client;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternData;
import com.ethan.emicraftingchains.crafting.CraftTarget;
import com.ethan.emicraftingchains.mixin.client.BoMNodeAccessor;
import com.ethan.emicraftingchains.mixin.client.BoMScreenAccessor;
import com.ethan.emicraftingchains.network.CraftNetwork;
import com.ethan.emicraftingchains.storage.StackAmount;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ChainCraftHandler {
    private static final int BUTTON_WIDTH = 170;
    private static final long AVAILABILITY_REFRESH_MILLIS = 5_000L;
    private static final Map<BoMScreen, Controls> CONTROLS = new WeakHashMap<>();
    private static final Map<MaterialTree, Set<MaterialNode>> PRUNED_BY_TREE = new IdentityHashMap<>();

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

        Button craft = Button.builder(
                        Component.translatable("button.emi_crafting_chains.craft_chain_checking"),
                        ignored -> craftTree(screen))
                .bounds((screen.width - BUTTON_WIDTH) / 2, screen.height - 27, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.emi_crafting_chains.craft_chain")))
                .build();
        Controls controls = new Controls(craft);
        CONTROLS.put(screen, controls);
        event.addListener(craft);
        requestAvailability(screen, true);
    }

    /** EMI's tree screen does not call Screen.render, so render its vanilla children explicitly. */
    @SubscribeEvent
    public static void onScreenRendered(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof BoMScreen screen)) {
            return;
        }

        BoM.craftingMode = true;
        requestAvailability(screen, false);

        Controls controls = CONTROLS.get(screen);
        if (controls == null) {
            return;
        }
        List<TreeRequest> combined = createTreeRequests();
        boolean withinLimits = isWithinNetworkLimits(combined);
        controls.craft().active = !combined.isEmpty() && withinLimits;
        controls.craft().setMessage(buttonMessage());

        renderPrunedBranches(screen, event.getGuiGraphics());
        controls.craft().render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
    }

    /** Handle our controls before EMI interprets the coordinates as tree interactions. */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof BoMScreen screen)) {
            return;
        }
        Controls controls = CONTROLS.get(screen);
        if (controls == null) {
            return;
        }
        if (controls.craft().visible
                && controls.craft().mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 1) {
            MaterialNode clicked = findNodeAt(screen, event.getMouseX(), event.getMouseY());
            if (clicked != null) {
                togglePruned(screen, clicked);
                event.setCanceled(true);
            }
        }
    }

    public static EmiPlayerInventory getSyncedInventory(Player player) {
        return syncedInventory == null ? EmiPlayerInventory.of(player) : syncedInventory;
    }

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

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        MultiblockPatternEncodingClient.reset();
        CONTROLS.clear();
        PRUNED_BY_TREE.clear();
        syncedInventory = null;
        lastRequestSignature = null;
        maximumBatches = -1;
    }

    static boolean isMultiblockRecipe(EmiRecipe recipe) {
        if (recipe == null || recipe.getCategory() == null || recipe.getCategory().getId() == null) {
            return false;
        }
        String path = recipe.getCategory().getId().getPath().toLowerCase();
        return path.contains("multiblock") && !recipe.getInputs().isEmpty() && !recipe.getOutputs().isEmpty();
    }

    private static Component buttonMessage() {
        if (maximumBatches < 0) {
            return Component.translatable("button.emi_crafting_chains.craft_chain_checking");
        }
        Component message = Component.translatable(
                "button.emi_crafting_chains.craft_chain_max", maximumBatches);
        MaterialTree tree = BoM.tree;
        EmiRecipe rootRecipe = tree == null || tree.goal == null ? null : tree.goal.recipe;
        if (tree != null && !isMultiblockRecipe(rootRecipe) && tree.batches > maximumBatches) {
            return message.copy().withStyle(ChatFormatting.RED);
        }
        if (isMultiblockRecipe(rootRecipe) && maximumBatches == 0) {
            return message.copy().withStyle(ChatFormatting.RED);
        }
        return message;
    }

    private static void requestAvailability(BoMScreen screen, boolean force) {
        List<TreeRequest> requests = createTreeRequests();
        if (requests.isEmpty()) {
            return;
        }
        if (!isWithinNetworkLimits(requests)) {
            maximumBatches = 0;
            return;
        }
        String signature = requests.stream().map(TreeRequest::signature).reduce((a, b) -> a + ";" + b).orElse("");

        long now = Util.getMillis();
        boolean changed = !signature.equals(lastRequestSignature);
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
        lastRequestSignature = signature;
        lastRequestMillis = now;
        CraftNetwork.requestAvailability(requestId, flattenTargets(requests), flattenQueries(requests));
    }

    private static List<TreeRequest> createTreeRequests() {
        TreeRequest request = createTreeRequest(BoM.tree);
        return request == null ? List.of() : List.of(request);
    }

    private static TreeRequest createTreeRequest(MaterialTree tree) {
        if (tree == null || tree.goal == null || tree.goal.recipe == null) {
            return null;
        }
        tree.recalculate();
        if (isDirectlyPruned(tree, tree.goal)) {
            return null;
        }

        EmiRecipe rootRecipe = tree.goal.recipe;
        ResourceLocation rootRecipeId = rootRecipe.getId();
        EmiStack rootOutput = rootRecipe.getOutputs().stream()
                .filter(stack -> !stack.getItemStack().isEmpty())
                .findFirst()
                .orElse(null);
        if (rootOutput == null) {
            return null;
        }

        List<ItemStack> queriedStacks = new ArrayList<>();
        List<CraftTarget> targets;
        if (isMultiblockRecipe(rootRecipe)) {
            targets = createMultiblockTargets(tree, rootRecipe, tree.goal, queriedStacks);
        } else {
            if (rootRecipeId == null) {
                return null;
            }
            Set<ResourceLocation> recipes = new LinkedHashSet<>();
            collectRecipes(tree, tree.goal, recipes);
            List<ItemStack> suppliedOnly = new ArrayList<>();
            collectSuppliedOnly(tree, tree.goal, suppliedOnly);
            if (tree.goal.children != null) {
                for (MaterialNode child : tree.goal.children) {
                    collectQueriedStacks(tree, child, queriedStacks);
                }
            }
            int batches = (int) Math.max(1L, Math.min(tree.batches, CraftNetwork.MAX_CHAIN_BATCHES));
            targets = List.of(CraftTarget.recipe(
                    rootOutput.getItemStack(), rootRecipeId, recipes.stream().toList(), suppliedOnly, batches));
        }
        if (targets.isEmpty() || targets.size() > CraftNetwork.MAX_CHAIN_TARGETS) {
            return null;
        }

        String signature = targetSignature(rootRecipeId, targets);
        ItemStack output = rootOutput.getItemStack().copy();
        output.setCount(1);
        return new TreeRequest(output, rootRecipeId, List.copyOf(targets), List.copyOf(queriedStacks), signature);
    }

    private static List<CraftTarget> createMultiblockTargets(
            MaterialTree tree,
            EmiRecipe recipe,
            MaterialNode goal,
            List<ItemStack> queriedStacks
    ) {
        List<CraftTarget> targets = new ArrayList<>();
        List<MaterialNode> children = goal.children == null ? List.of() : goal.children;
        int childIndex = 0;
        for (EmiIngredient input : recipe.getInputs()) {
            MaterialNode child = childIndex < children.size() ? children.get(childIndex++) : null;
            EmiStack selected = input.getEmiStacks().stream()
                    .filter(stack -> !stack.getItemStack().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                continue;
            }
            if (child != null && isDirectlyPruned(tree, child)) {
                continue;
            }
            ItemStack stack = selected.getItemStack().copy();
            stack.setCount(1);
            long inputAmount = Math.max(1L, input.getAmount());
            long batches = Math.max(1L, tree.batches);
            long rawAmount = inputAmount > 1_000_000L / batches
                    ? 1_000_000L
                    : inputAmount * batches;
            int amount = (int) Math.min(rawAmount, 1_000_000L);
            Set<ResourceLocation> recipes = new LinkedHashSet<>();
            List<ItemStack> suppliedOnly = new ArrayList<>();
            if (child != null) {
                collectRecipes(tree, child, recipes);
                collectSuppliedOnly(tree, child, suppliedOnly);
                collectQueriedStacks(tree, child, queriedStacks);
            }
            targets.add(CraftTarget.material(stack, recipes.stream().toList(), suppliedOnly, amount));
        }
        return targets;
    }

    private static void craftTree(BoMScreen screen) {
        List<TreeRequest> requests = createTreeRequests();
        if (!isWithinNetworkLimits(requests)) {
            return;
        }
        List<CraftTarget> targets = flattenTargets(requests);
        EmiCraftingChains.LOGGER.info(
                "Submitting EMI craft chain: roots={}, targets={}", requests.size(), targets.size());
        CraftNetwork.sendChainRequest(targets);

        Minecraft minecraft = Minecraft.getInstance();
        if (screen.old != null) {
            minecraft.setScreen(screen.old);
        }
    }

    public static MultiblockPatternData createCurrentMultiblockPattern() {
        MaterialTree tree = BoM.tree;
        if (tree == null || tree.goal == null || !isMultiblockRecipe(tree.goal.recipe)
                || isDirectlyPruned(tree, tree.goal)) {
            return null;
        }
        List<StackAmount> materials = new ArrayList<>();
        List<CraftTarget> targets = createMultiblockTargets(
                tree, tree.goal.recipe, tree.goal, new ArrayList<>());
        for (CraftTarget target : targets) {
            mergeMaterial(materials, target.stack(), target.amount());
        }
        if (materials.isEmpty()) {
            return null;
        }
        ItemStack root = tree.goal.recipe.getOutputs().stream()
                .map(EmiStack::getItemStack)
                .filter(stack -> !stack.isEmpty())
                .findFirst()
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
        if (root.isEmpty()) {
            return null;
        }
        root.setCount(1);
        return new MultiblockPatternData(root, tree.goal.recipe.getId(), materials);
    }

    private static void mergeMaterial(List<StackAmount> materials, ItemStack stack, long amount) {
        for (int i = 0; i < materials.size(); i++) {
            StackAmount existing = materials.get(i);
            if (ItemStack.isSameItemSameTags(existing.stack(), stack)) {
                long merged = Math.min(1_000_000L, existing.amount() + amount);
                materials.set(i, new StackAmount(existing.stack(), merged));
                return;
            }
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        materials.add(new StackAmount(copy, Math.min(1_000_000L, amount)));
    }

    private static List<CraftTarget> flattenTargets(List<TreeRequest> requests) {
        return requests.stream().flatMap(request -> request.targets().stream()).toList();
    }

    private static List<ItemStack> flattenQueries(List<TreeRequest> requests) {
        List<ItemStack> queries = new ArrayList<>();
        for (TreeRequest request : requests) {
            for (ItemStack stack : request.queriedStacks()) {
                addUniqueStack(queries, stack);
                if (queries.size() >= CraftNetwork.MAX_AVAILABILITY_STACKS) {
                    return List.copyOf(queries);
                }
            }
        }
        return List.copyOf(queries);
    }

    private static boolean isWithinNetworkLimits(List<TreeRequest> requests) {
        int targets = 0;
        int recipeIds = 0;
        int suppliedOnly = 0;
        for (TreeRequest request : requests) {
            targets += request.targets().size();
            if (targets > CraftNetwork.MAX_CHAIN_TARGETS) {
                return false;
            }
            for (CraftTarget target : request.targets()) {
                recipeIds += target.chainRecipeIds().size();
                if (recipeIds > CraftNetwork.MAX_TOTAL_RECIPE_IDS) {
                    return false;
                }
                suppliedOnly += target.suppliedOnly().size();
                if (target.suppliedOnly().size() > CraftNetwork.MAX_SUPPLIED_ONLY_STACKS
                        || suppliedOnly > CraftNetwork.MAX_TOTAL_SUPPLIED_ONLY_STACKS) {
                    return false;
                }
            }
        }
        return targets > 0;
    }

    private static String targetSignature(ResourceLocation rootRecipeId, List<CraftTarget> targets) {
        StringBuilder signature = new StringBuilder(String.valueOf(rootRecipeId));
        for (CraftTarget target : targets) {
            signature.append('|')
                    .append(target.stack().getItem()).append(':')
                    .append(target.stack().getTag()).append(':')
                    .append(target.amount()).append(':')
                    .append(target.useExisting()).append(':')
                    .append(target.preferredRecipeId()).append(':')
                    .append(target.chainRecipeIds()).append(':')
                    .append(target.suppliedOnly());
        }
        return signature.toString();
    }

    private static Set<MaterialNode> prunedNodes(MaterialTree tree) {
        return PRUNED_BY_TREE.computeIfAbsent(
                tree, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static MaterialNode findNodeAt(BoMScreen screen, double mouseX, double mouseY) {
        MaterialTree tree = BoM.tree;
        if (tree == null) {
            return null;
        }
        try {
            BoMScreenAccessor accessor = (BoMScreenAccessor) screen;
            float scale = screen.getScale();
            double logicalX = (mouseX - screen.width / 2d) / scale - accessor.emiCraftingChains$getOffX();
            double logicalY = (mouseY - screen.height / 2d) / scale - accessor.emiCraftingChains$getOffY();
            for (Object object : accessor.emiCraftingChains$getNodes()) {
                BoMNodeAccessor node = (BoMNodeAccessor) object;
                double halfWidth = node.emiCraftingChains$getWidth() / 2d;
                if (logicalX >= node.emiCraftingChains$getX() - halfWidth
                        && logicalX <= node.emiCraftingChains$getX() + halfWidth
                        && logicalY >= node.emiCraftingChains$getY() - 11
                        && logicalY <= node.emiCraftingChains$getY() + 10) {
                    return node.emiCraftingChains$getNode();
                }
            }
        } catch (RuntimeException ignored) {
            // A future EMI layout can disable pruning without affecting crafting.
        }
        return null;
    }

    private static void togglePruned(BoMScreen screen, MaterialNode clicked) {
        MaterialTree tree = BoM.tree;
        if (tree == null) {
            return;
        }
        Set<MaterialNode> pruned = prunedNodes(tree);
        MaterialNode prunedAncestor = findPrunedAncestor(tree, clicked);
        boolean restoring = prunedAncestor != null;
        MaterialNode actionNode = restoring ? prunedAncestor : clicked;
        if (restoring) {
            pruned.remove(prunedAncestor);
        } else {
            pruned.removeIf(existing -> containsNode(clicked, existing));
            pruned.add(clicked);
        }

        syncedInventory = null;
        lastRequestSignature = null;
        maximumBatches = -1;
        screen.recalculateTree();
        requestAvailability(screen, true);

        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.translatable(
                    restoring
                            ? "message.emi_crafting_chains.branch_restored"
                            : "message.emi_crafting_chains.branch_pruned",
                    nodeName(actionNode)
            ), true);
        }
    }

    private static Component nodeName(MaterialNode node) {
        if (node != null && node.ingredient != null) {
            for (EmiStack stack : node.ingredient.getEmiStacks()) {
                if (!stack.getItemStack().isEmpty()) {
                    return stack.getItemStack().getHoverName();
                }
            }
        }
        return Component.translatable("message.emi_crafting_chains.branch");
    }

    private static MaterialNode findPrunedAncestor(MaterialTree tree, MaterialNode node) {
        for (MaterialNode pruned : prunedNodes(tree)) {
            if (containsNode(pruned, node)) {
                return pruned;
            }
        }
        return null;
    }

    private static boolean isDirectlyPruned(MaterialTree tree, MaterialNode node) {
        return tree != null && node != null && prunedNodes(tree).contains(node);
    }

    private static boolean containsNode(MaterialNode root, MaterialNode target) {
        return containsNode(root, target, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean containsNode(MaterialNode root, MaterialNode target, Set<MaterialNode> visited) {
        if (root == null || !visited.add(root)) {
            return false;
        }
        if (root == target) {
            return true;
        }
        if (root.children != null) {
            for (MaterialNode child : root.children) {
                if (containsNode(child, target, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void renderPrunedBranches(BoMScreen screen, GuiGraphics graphics) {
        MaterialTree tree = BoM.tree;
        if (tree == null) {
            return;
        }
        Set<MaterialNode> pruned = prunedNodes(tree);
        if (pruned.isEmpty()) {
            return;
        }
        Set<MaterialNode> affected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MaterialNode node : pruned) {
            collectNodes(node, affected);
        }
        try {
            BoMScreenAccessor accessor = (BoMScreenAccessor) screen;
            float scale = screen.getScale();
            int radius = Math.max(6, Math.round(8 * scale));
            for (Object object : accessor.emiCraftingChains$getNodes()) {
                BoMNodeAccessor node = (BoMNodeAccessor) object;
                if (!affected.contains(node.emiCraftingChains$getNode())) {
                    continue;
                }
                int x = Math.round(screen.width / 2f + scale * (float) (
                        accessor.emiCraftingChains$getOffX() + node.emiCraftingChains$getX()));
                int y = Math.round(screen.height / 2f + scale * (float) (
                        accessor.emiCraftingChains$getOffY() + node.emiCraftingChains$getY()));
                drawCross(graphics, x, y, radius);
            }
        } catch (RuntimeException ignored) {
            // The pruning request still works if only the optional overlay fails.
        }
    }

    private static void collectNodes(MaterialNode node, Set<MaterialNode> nodes) {
        if (node == null || !nodes.add(node)) {
            return;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectNodes(child, nodes);
            }
        }
    }

    private static void drawCross(GuiGraphics graphics, int centerX, int centerY, int radius) {
        int color = 0xFFFF5555;
        for (int offset = -radius; offset <= radius; offset++) {
            graphics.fill(centerX + offset, centerY + offset,
                    centerX + offset + 2, centerY + offset + 2, color);
            graphics.fill(centerX + offset, centerY - offset,
                    centerX + offset + 2, centerY - offset + 2, color);
        }
    }

    private static void collectRecipes(MaterialTree tree, MaterialNode node, Set<ResourceLocation> recipes) {
        if (node == null || recipes.size() >= CraftNetwork.MAX_CHAIN_RECIPES) {
            return;
        }
        if (isDirectlyPruned(tree, node)) {
            return;
        }
        if (node.recipe != null && node.recipe.getId() != null) {
            recipes.add(node.recipe.getId());
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectRecipes(tree, child, recipes);
            }
        }
    }

    private static void collectSuppliedOnly(
            MaterialTree tree,
            MaterialNode node,
            List<ItemStack> suppliedOnly
    ) {
        if (node == null || suppliedOnly.size() >= CraftNetwork.MAX_SUPPLIED_ONLY_STACKS) {
            return;
        }
        if (isDirectlyPruned(tree, node)) {
            if (node.ingredient != null) {
                for (EmiStack stack : node.ingredient.getEmiStacks()) {
                    ItemStack item = stack.getItemStack();
                    if (!item.isEmpty()) {
                        item = item.copy();
                        item.setCount(1);
                        addUniqueStack(suppliedOnly, item);
                        if (suppliedOnly.size() >= CraftNetwork.MAX_SUPPLIED_ONLY_STACKS) {
                            return;
                        }
                    }
                }
            }
            return;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectSuppliedOnly(tree, child, suppliedOnly);
            }
        }
    }

    private static void collectQueriedStacks(
            MaterialTree tree,
            MaterialNode node,
            List<ItemStack> queriedStacks
    ) {
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
        if (isDirectlyPruned(tree, node)) {
            return;
        }
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                collectQueriedStacks(tree, child, queriedStacks);
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

    private record Controls(Button craft) {
    }

    private record TreeRequest(
            ItemStack output,
            ResourceLocation rootRecipeId,
            List<CraftTarget> targets,
            List<ItemStack> queriedStacks,
            String signature
    ) {
    }
}
