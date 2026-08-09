package com.ethan.emicraftingchains.crafting;

import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CraftPlan(
        List<StackAmount> deliveries,
        List<StackAmount> requirements,
        List<StackAmount> surplus,
        List<CraftedStep> craftedSteps,
        List<CraftedStep> visualSteps,
        List<Integer> completedAmounts
) {
    public CraftPlan(
            List<StackAmount> deliveries,
            List<StackAmount> requirements,
            List<StackAmount> surplus,
            List<CraftedStep> craftedSteps,
            List<Integer> completedAmounts
    ) {
        this(deliveries, requirements, surplus, craftedSteps, List.of(), completedAmounts);
    }

    public CraftPlan {
        deliveries = List.copyOf(deliveries);
        requirements = List.copyOf(requirements);
        surplus = List.copyOf(surplus);
        craftedSteps = craftedSteps.stream()
                .map(step -> new CraftedStep(
                        step.recipe(), step.inputs(), step.output(), step.remainders()))
                .toList();
        visualSteps = compactSteps(craftedSteps);
        completedAmounts = List.copyOf(completedAmounts);
    }

    public ItemStack delivery() {
        return deliveries.isEmpty() ? ItemStack.EMPTY : deliveries.get(deliveries.size() - 1).stack().copy();
    }

    public int craftedBatches() {
        return completedAmounts.isEmpty() ? 0 : completedAmounts.get(completedAmounts.size() - 1);
    }

    /**
     * Repeated executions of the same recipe with the same concrete ingredient
     * choices are one visual operation. The displayed input and output counts
     * are multiplied by the number of executions, while insertion order keeps
     * the chain's leaf-to-root progression.
     */
    private static List<CraftedStep> compactSteps(List<CraftedStep> rawSteps) {
        Map<StepKey, MutableStep> compacted = new LinkedHashMap<>();
        for (CraftedStep step : rawSteps) {
            StepKey key = StepKey.of(step);
            compacted.computeIfAbsent(key, ignored -> new MutableStep(step)).add(step);
        }

        List<MutableStep> pending = new ArrayList<>(compacted.values());
        List<CraftedStep> ordered = new ArrayList<>(pending.size());
        while (!pending.isEmpty()) {
            int readyIndex = -1;
            for (int candidateIndex = 0; candidateIndex < pending.size(); candidateIndex++) {
                MutableStep candidate = pending.get(candidateIndex);
                boolean waitingForIngredient = false;
                for (int producerIndex = 0; producerIndex < pending.size(); producerIndex++) {
                    if (producerIndex != candidateIndex
                            && candidate.dependsOn(pending.get(producerIndex))) {
                        waitingForIngredient = true;
                        break;
                    }
                }
                if (!waitingForIngredient) {
                    readyIndex = candidateIndex;
                    break;
                }
            }

            // Source-backed recipe cycles can create a display-only cycle.
            // Preserve the planner's first-seen order in that rare case.
            if (readyIndex < 0) {
                readyIndex = 0;
            }
            ordered.add(pending.remove(readyIndex).finish());
        }
        return List.copyOf(ordered);
    }

    public record CraftedStep(
            CraftingRecipe recipe,
            List<ItemStack> inputs,
            ItemStack output,
            List<ItemStack> remainders
    ) {
        public CraftedStep(CraftingRecipe recipe, List<ItemStack> inputs, ItemStack output) {
            this(recipe, inputs, output, List.of());
        }

        public CraftedStep {
            inputs = inputs.stream().map(ItemStack::copy).toList();
            output = output.copy();
            remainders = remainders.stream().map(ItemStack::copy).toList();
        }
    }

    private record StepKey(
            net.minecraft.resources.ResourceLocation recipeId,
            List<StackKey> inputs,
            StackKey output
    ) {
        private static StepKey of(CraftedStep step) {
            return new StepKey(
                    step.recipe().getId(),
                    step.inputs().stream().map(StackKey::forBatching).toList(),
                    StackKey.of(step.output())
            );
        }
    }

    private static final class MutableStep {
        private final CraftingRecipe recipe;
        private final List<ItemStack> inputs;
        private final ItemStack output;
        private final List<ItemStack> lastRemainders;

        private MutableStep(CraftedStep template) {
            recipe = template.recipe();
            inputs = new ArrayList<>(template.inputs().size());
            lastRemainders = new ArrayList<>(template.inputs().size());
            for (ItemStack stack : template.inputs()) {
                ItemStack emptyCount = stack.copy();
                emptyCount.setCount(0);
                inputs.add(emptyCount);
                lastRemainders.add(ItemStack.EMPTY);
            }
            output = template.output().copy();
            output.setCount(0);
        }

        private void add(CraftedStep step) {
            for (int index = 0; index < inputs.size(); index++) {
                ItemStack addition = step.inputs().get(index);
                if (addition.isEmpty()) {
                    continue;
                }
                if (StackKey.isReusableCraftingTool(addition)) {
                    ItemStack previousRemainder = lastRemainders.get(index);
                    if (previousRemainder.isEmpty()
                            || !ItemStack.isSameItemSameTags(previousRemainder, addition)) {
                        inputs.get(index).grow(1);
                    }
                    ItemStack nextRemainder = index < step.remainders().size()
                            ? step.remainders().get(index).copy()
                            : ItemStack.EMPTY;
                    lastRemainders.set(index, nextRemainder);
                } else {
                    inputs.get(index).grow(addition.getCount());
                }
            }
            output.grow(step.output().getCount());
        }

        private CraftedStep finish() {
            return new CraftedStep(recipe, inputs, output, List.of());
        }

        private boolean dependsOn(MutableStep producer) {
            if (producer.output.isEmpty()) {
                return false;
            }
            for (ItemStack input : inputs) {
                if (!input.isEmpty() && (ItemStack.isSameItemSameTags(input, producer.output)
                        || StackKey.sameForBatching(input, producer.output))) {
                    return true;
                }
            }
            return false;
        }
    }
}
