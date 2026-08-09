package com.ethan.emicraftingchains.crafting;

import com.ethan.emicraftingchains.crafting.CraftPlan.CraftedStep;
import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive, backtracking planner for ordinary 3x3 crafting recipes.
 * Planning mutates only an in-memory stock ledger; the caller commits afterward.
 */
public final class RecipePlanner {
    private static final int MAX_DEPTH = 40;
    private static final int MAX_ATTEMPTS = 2_048;
    private static final int MAX_SUCCESSFUL_CRAFTS = 512;
    private static final PlannerMenu MENU = new PlannerMenu();

    private final ServerPlayer player;
    private final List<Stock> stock = new ArrayList<>();
    private final Map<StackKey, Long> requirements = new LinkedHashMap<>();
    private final List<CraftedStep> craftedSteps = new ArrayList<>();
    private final Map<Item, List<CraftingRecipe>> recipesByOutput = new HashMap<>();
    private final Map<Item, ResourceLocation> preferredRecipeByOutput = new HashMap<>();
    private int attempts;

    public RecipePlanner(
            ServerPlayer player,
            List<StackAmount> available,
            List<ResourceLocation> chainRecipeIds
    ) {
        this.player = player;
        for (StackAmount entry : available) {
            addStock(entry.stack(), entry.amount(), true);
        }
        for (CraftingRecipe recipe : player.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack output = preview(recipe);
            if (!output.isEmpty() && hasIngredients(recipe)) {
                recipesByOutput.computeIfAbsent(output.getItem(), ignored -> new ArrayList<>()).add(recipe);
            }
        }
        for (ResourceLocation recipeId : chainRecipeIds) {
            Recipe<?> recipe = player.level().getRecipeManager().byKey(recipeId).orElse(null);
            if (recipe == null) {
                continue;
            }
            ItemStack output = recipe.getResultItem(player.level().registryAccess());
            if (!output.isEmpty()) {
                preferredRecipeByOutput.putIfAbsent(output.getItem(), recipeId);
            }
        }
    }

    public CraftPlan plan(Item requestedItem, ResourceLocation preferredRecipe, int requestedBatches)
            throws PlanFailure {
        CraftingRecipe root = selectRootRecipe(requestedItem, preferredRecipe);
        ItemStack preview = preview(root);
        if (preview.isEmpty() || preview.getItem() != requestedItem) {
            throw new PlanFailure(Component.translatable("message.emi_crafting_chains.invalid_recipe"));
        }

        ItemStack deliveryTemplate = ItemStack.EMPTY;
        int deliveryCount = 0;
        int maximum = preview.getMaxStackSize();
        int batchTarget = Math.max(1, Math.min(requestedBatches, MAX_SUCCESSFUL_CRAFTS));
        int craftedBatches = 0;
        PlanFailure lastFailure = null;

        while (deliveryCount < maximum && craftedBatches < batchTarget) {
            State beforeBatch = snapshotState();
            try {
                Set<Item> activeOutputs = new HashSet<>();
                activeOutputs.add(requestedItem);
                ItemStack output = craftOne(root, activeOutputs, 0);
                if (output.isEmpty() || output.getItem() != requestedItem) {
                    throw new PlanFailure(Component.translatable("message.emi_crafting_chains.invalid_recipe"));
                }
                if (deliveryTemplate.isEmpty()) {
                    deliveryTemplate = output.copy();
                    deliveryTemplate.setCount(1);
                } else if (!ItemStack.isSameItemSameTags(deliveryTemplate, output)) {
                    throw new PlanFailure(Component.translatable("message.emi_crafting_chains.failed", preview.getHoverName()));
                }
                if (deliveryCount + output.getCount() > maximum) {
                    restoreState(beforeBatch);
                    break;
                }
                deliveryCount += output.getCount();
                craftedBatches++;
            } catch (PlanFailure failure) {
                restoreState(beforeBatch);
                lastFailure = failure;
                break;
            }
        }

        if (deliveryCount <= 0 || deliveryTemplate.isEmpty()) {
            throw lastFailure == null
                    ? new PlanFailure(Component.translatable("message.emi_crafting_chains.failed", preview.getHoverName()))
                    : lastFailure;
        }

        if (!takeExact(deliveryTemplate, deliveryCount, false)) {
            throw new PlanFailure(Component.translatable("message.emi_crafting_chains.failed", preview.getHoverName()));
        }

        ItemStack delivery = deliveryTemplate.copy();
        delivery.setCount(deliveryCount);
        return new CraftPlan(
                delivery,
                amountList(requirements),
                craftedSurplus(),
                List.copyOf(craftedSteps),
                craftedBatches
        );
    }

    private CraftingRecipe selectRootRecipe(Item requestedItem, ResourceLocation preferredRecipe)
            throws PlanFailure {
        if (preferredRecipe != null) {
            Recipe<?> recipe = player.level().getRecipeManager().byKey(preferredRecipe).orElse(null);
            if (!(recipe instanceof CraftingRecipe craftingRecipe)
                    || !hasIngredients(craftingRecipe)
                    || preview(craftingRecipe).getItem() != requestedItem) {
                throw new PlanFailure(Component.translatable("message.emi_crafting_chains.invalid_recipe"));
            }
            return craftingRecipe;
        }

        List<CraftingRecipe> recipes = recipesByOutput.getOrDefault(requestedItem, List.of());
        if (recipes.isEmpty()) {
            ItemStack requested = new ItemStack(requestedItem);
            throw new PlanFailure(Component.translatable("message.emi_crafting_chains.no_recipe", requested.getHoverName()));
        }
        return recipes.get(0);
    }

    private ItemStack craftOne(CraftingRecipe recipe, Set<Item> activeOutputs, int depth) throws PlanFailure {
        if (depth > MAX_DEPTH || ++attempts > MAX_ATTEMPTS || craftedSteps.size() >= MAX_SUCCESSFUL_CRAFTS) {
            throw tooComplex();
        }

        State before = snapshotState();
        try {
            NonNullList<Ingredient> ingredients = recipe.getIngredients();
            ItemStack[] selected = new ItemStack[ingredients.size()];
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < ingredients.size(); i++) {
                if (!ingredients.get(i).isEmpty()) {
                    order.add(i);
                }
            }
            order.sort(Comparator
                    .comparingInt((Integer index) -> ingredients.get(index).getItems().length)
                    .thenComparingInt(Integer::intValue));

            for (int index : order) {
                selected[index] = acquire(ingredients.get(index), activeOutputs, depth + 1);
            }

            CraftingContainer matrix = makeMatrix(recipe, ingredients, selected);
            if (!recipe.matches(matrix, player.level())) {
                throw new PlanFailure(Component.translatable(
                        "message.emi_crafting_chains.failed", preview(recipe).getHoverName()));
            }

            ItemStack output = recipe.assemble(matrix, player.level().registryAccess());
            if (output.isEmpty()) {
                throw new PlanFailure(Component.translatable(
                        "message.emi_crafting_chains.failed", preview(recipe).getHoverName()));
            }

            NonNullList<ItemStack> remaining = recipe.getRemainingItems(matrix);
            for (ItemStack remainder : remaining) {
                if (!remainder.isEmpty()) {
                    addStock(remainder, remainder.getCount(), false);
                }
            }
            addStock(output, output.getCount(), false);
            craftedSteps.add(new CraftedStep(recipe, matrixItems(matrix), output));
            return output.copy();
        } catch (PlanFailure failure) {
            restoreState(before);
            throw failure;
        } catch (RuntimeException failure) {
            restoreState(before);
            throw new PlanFailure(Component.translatable(
                    "message.emi_crafting_chains.failed", preview(recipe).getHoverName()));
        }
    }

    private ItemStack acquire(Ingredient ingredient, Set<Item> activeOutputs, int depth) throws PlanFailure {
        ItemStack existing = takeMatching(ingredient);
        if (!existing.isEmpty()) {
            return existing;
        }
        if (depth > MAX_DEPTH) {
            throw tooComplex();
        }

        List<CraftingRecipe> candidates = new ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        for (ItemStack option : ingredient.getItems()) {
            for (CraftingRecipe recipe : recipesByOutput.getOrDefault(option.getItem(), List.of())) {
                if (ingredient.test(preview(recipe)) && seen.add(recipe.getId())) {
                    candidates.add(recipe);
                }
            }
        }

        Set<ResourceLocation> selectedRecipes = new HashSet<>();
        for (ItemStack option : ingredient.getItems()) {
            ResourceLocation selected = preferredRecipeByOutput.get(option.getItem());
            if (selected != null) {
                selectedRecipes.add(selected);
            }
        }
        if (!selectedRecipes.isEmpty()) {
            candidates.removeIf(recipe -> !selectedRecipes.contains(recipe.getId()));
            if (candidates.isEmpty()) {
                throw new PlanFailure(Component.translatable("message.emi_crafting_chains.invalid_chain"));
            }
        }

        for (CraftingRecipe candidate : candidates) {
            Item outputItem = preview(candidate).getItem();
            if (activeOutputs.contains(outputItem)) {
                continue;
            }
            State beforeCandidate = snapshotState();
            activeOutputs.add(outputItem);
            try {
                craftOne(candidate, activeOutputs, depth);
                ItemStack made = takeMatching(ingredient);
                if (!made.isEmpty()) {
                    return made;
                }
            } catch (PlanFailure ignored) {
                // Try the next recipe or tag alternative from the unchanged ledger.
            } finally {
                activeOutputs.remove(outputItem);
            }
            restoreState(beforeCandidate);
        }

        ItemStack[] options = ingredient.getItems();
        Component missing = options.length == 0
                ? Component.literal("unknown ingredient")
                : options[0].getHoverName();
        throw new PlanFailure(Component.translatable("message.emi_crafting_chains.missing", missing));
    }

    private ItemStack takeMatching(Ingredient ingredient) {
        for (boolean sourced : new boolean[]{false, true}) {
            for (Stock entry : stock) {
                if (entry.sourced == sourced && entry.count > 0 && ingredient.test(entry.template)) {
                    entry.count--;
                    if (entry.sourced) {
                        requirements.merge(StackKey.of(entry.template), 1L, Long::sum);
                    }
                    ItemStack result = entry.template.copy();
                    result.setCount(1);
                    return result;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean takeExact(ItemStack template, long amount, boolean sourceFirst) {
        State before = snapshotState();
        long remaining = amount;
        boolean[] order = sourceFirst ? new boolean[]{true, false} : new boolean[]{false, true};
        for (boolean sourced : order) {
            for (Stock entry : stock) {
                if (entry.sourced != sourced || entry.count <= 0
                        || !ItemStack.isSameItemSameTags(entry.template, template)) {
                    continue;
                }
                long taken = Math.min(remaining, entry.count);
                entry.count -= taken;
                remaining -= taken;
                if (entry.sourced) {
                    requirements.merge(StackKey.of(entry.template), taken, Long::sum);
                }
                if (remaining == 0) {
                    return true;
                }
            }
        }
        restoreState(before);
        return false;
    }

    private CraftingContainer makeMatrix(
            CraftingRecipe recipe,
            NonNullList<Ingredient> ingredients,
            ItemStack[] selected
    ) {
        CraftingContainer matrix = new TransientCraftingContainer(MENU, 3, 3);
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            for (int i = 0; i < selected.length; i++) {
                if (selected[i] != null && !selected[i].isEmpty()) {
                    matrix.setItem((i % width) + (i / width) * 3, selected[i]);
                }
            }
        } else {
            int slot = 0;
            for (int i = 0; i < ingredients.size(); i++) {
                if (!ingredients.get(i).isEmpty() && selected[i] != null) {
                    matrix.setItem(slot++, selected[i]);
                }
            }
        }
        return matrix;
    }

    private List<ItemStack> matrixItems(CraftingContainer matrix) {
        List<ItemStack> inputs = new ArrayList<>(matrix.getContainerSize());
        for (int i = 0; i < matrix.getContainerSize(); i++) {
            inputs.add(matrix.getItem(i).copy());
        }
        return inputs;
    }

    private ItemStack preview(CraftingRecipe recipe) {
        return recipe.getResultItem(player.level().registryAccess());
    }

    private boolean hasIngredients(CraftingRecipe recipe) {
        return recipe.getIngredients().stream().anyMatch(ingredient -> !ingredient.isEmpty());
    }

    private void addStock(ItemStack template, long amount, boolean sourced) {
        if (template.isEmpty() || amount <= 0) {
            return;
        }
        for (Stock entry : stock) {
            if (entry.sourced == sourced && ItemStack.isSameItemSameTags(entry.template, template)) {
                entry.count += amount;
                return;
            }
        }
        ItemStack normalized = template.copy();
        normalized.setCount(1);
        stock.add(new Stock(normalized, amount, sourced));
    }

    private List<StackAmount> craftedSurplus() {
        Map<StackKey, Long> totals = new LinkedHashMap<>();
        for (Stock entry : stock) {
            if (!entry.sourced && entry.count > 0) {
                totals.merge(StackKey.of(entry.template), entry.count, Long::sum);
            }
        }
        return amountList(totals);
    }

    private List<StackAmount> amountList(Map<StackKey, Long> amounts) {
        return amounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new StackAmount(entry.getKey().toStack(), entry.getValue()))
                .toList();
    }

    private State snapshotState() {
        List<Stock> stockCopy = stock.stream().map(Stock::copy).toList();
        return new State(stockCopy, new LinkedHashMap<>(requirements), craftedSteps.size());
    }

    private void restoreState(State state) {
        stock.clear();
        state.stock().stream().map(Stock::copy).forEach(stock::add);
        requirements.clear();
        requirements.putAll(state.requirements());
        while (craftedSteps.size() > state.craftedStepCount()) {
            craftedSteps.remove(craftedSteps.size() - 1);
        }
    }

    private PlanFailure tooComplex() {
        return new PlanFailure(Component.translatable("message.emi_crafting_chains.too_complex"));
    }

    private static final class Stock {
        private final ItemStack template;
        private long count;
        private final boolean sourced;

        private Stock(ItemStack template, long count, boolean sourced) {
            this.template = template.copy();
            this.template.setCount(1);
            this.count = count;
            this.sourced = sourced;
        }

        private Stock copy() {
            return new Stock(template, count, sourced);
        }
    }

    private record State(List<Stock> stock, Map<StackKey, Long> requirements, int craftedStepCount) {
    }

    private static final class PlannerMenu extends AbstractContainerMenu {
        private PlannerMenu() {
            super(null, -1);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }
    }
}
