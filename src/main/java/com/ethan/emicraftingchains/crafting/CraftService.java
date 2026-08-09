package com.ethan.emicraftingchains.crafting;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.config.AutoCraftConfig;
import com.ethan.emicraftingchains.crafting.CraftPlan.CraftedStep;
import com.ethan.emicraftingchains.storage.ItemSources;
import com.ethan.emicraftingchains.storage.ItemSources.CommitResult;
import com.ethan.emicraftingchains.storage.ItemSources.SourceGroup;
import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CraftService {
    private static final Map<UUID, Long> LAST_REQUEST_TICK = new HashMap<>();

    private CraftService() {
    }

    public static void craft(
            ServerPlayer player,
            List<CraftTarget> targets
    ) {
        long gameTick = player.serverLevel().getGameTime();
        Long previousTick = LAST_REQUEST_TICK.put(player.getUUID(), gameTick);
        if (previousTick != null && previousTick == gameTick) {
            return;
        }
        if (CraftJobManager.isBusy(player)) {
            player.displayClientMessage(Component.translatable("message.emi_crafting_chains.busy"), true);
            return;
        }
        if (!AutoCraftConfig.ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.emi_crafting_chains.disabled"), true);
            return;
        }
        if (!validTargets(targets)) {
            player.displayClientMessage(Component.translatable("message.emi_crafting_chains.request_too_large"), true);
            return;
        }

        SourceGroup sources = ItemSources.forPlayer(player);
        final CraftPlan plan;
        try {
            plan = new RecipePlanner(player, sources.snapshot()).plan(targets);
        } catch (PlanFailure failure) {
            EmiCraftingChains.LOGGER.info(
                    "Rejected EMI craft chain for {} (targets={}): {}",
                    player.getGameProfile().getName(),
                    targets.size(),
                    failure.messageComponent().getString()
            );
            player.displayClientMessage(failure.messageComponent(), true);
            return;
        }

        EmiCraftingChains.LOGGER.info(
                "Planned EMI craft chain for {}: deliveries={}, crafts={}, visual steps={}, sourced stacks={}",
                player.getGameProfile().getName(),
                plan.deliveries().size(),
                plan.craftedSteps().size(),
                plan.visualSteps().size(),
                plan.requirements().size()
        );

        for (StackAmount requirement : plan.requirements()) {
            if (!sources.canExtract(requirement.stack(), requirement.amount())) {
                EmiCraftingChains.LOGGER.info(
                        "EMI craft chain changed before start for {}: missing {}x{}",
                        player.getGameProfile().getName(),
                        BuiltInRegistries.ITEM.getKey(requirement.stack().getItem()),
                        requirement.amount()
                );
                player.displayClientMessage(Component.translatable("message.emi_crafting_chains.changed"), true);
                return;
            }
        }

        CraftJobManager.start(player, plan, sources);
    }

    /**
     * Uses the same authoritative item sources and recursive planner as an
     * actual craft. The returned inventory is restricted to stack variants
     * requested by the open EMI tree, keeping terminal sync packets small.
     */
    public static ChainAvailability analyze(
            ServerPlayer player,
            List<CraftTarget> targets,
            List<ItemStack> queriedStacks,
            int maximumBatches
    ) {
        SourceGroup sources = ItemSources.forPlayer(player);
        List<StackAmount> snapshot = sources.snapshot();

        Map<StackKey, Long> availableByStack = new LinkedHashMap<>();
        for (StackAmount entry : snapshot) {
            if (entry.amount() <= 0) {
                continue;
            }
            availableByStack.merge(
                    StackKey.of(entry.stack()),
                    entry.amount(),
                    CraftService::saturatedAdd
            );
        }

        List<StackAmount> relevantAvailability = new ArrayList<>();
        Map<StackKey, Boolean> seen = new LinkedHashMap<>();
        for (ItemStack queried : queriedStacks) {
            if (queried.isEmpty()) {
                continue;
            }
            StackKey key = StackKey.of(queried);
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }
            long available = availableByStack.getOrDefault(key, 0L);
            if (available > 0) {
                relevantAvailability.add(new StackAmount(queried, available));
            }
        }

        int craftableBatches = 0;
        if (AutoCraftConfig.ENABLED.get() && validTargets(targets)) {
            try {
                List<CraftTarget> maximumTargets = new ArrayList<>(targets);
                CraftTarget current = maximumTargets.get(maximumTargets.size() - 1);
                if (!current.useExisting()) {
                    maximumTargets.set(maximumTargets.size() - 1, new CraftTarget(
                            current.stack(),
                            current.preferredRecipeId(),
                            current.chainRecipeIds(),
                            current.suppliedOnly(),
                            Math.min(maximumBatches, AutoCraftConfig.MAX_BATCHES_PER_TARGET.get()),
                            false
                    ));
                }
                CraftPlan maximumPlan = new RecipePlanner(player, snapshot).plan(maximumTargets);
                craftableBatches = current.useExisting() ? 1 : maximumPlan.craftedBatches();
            } catch (PlanFailure ignored) {
                // Zero is the useful availability result for an invalid or
                // presently impossible chain; crafting will still report the
                // detailed failure if the player presses the button.
            }
        }

        return new ChainAvailability(List.copyOf(relevantAvailability), craftableBatches);
    }

    private static boolean validTargets(List<CraftTarget> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        long materialItems = 0;
        for (CraftTarget target : targets) {
            ItemStack stack = target.stack();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (stack.isEmpty() || stack.getItem() == Items.AIR
                    || !BuiltInRegistries.ITEM.containsKey(itemId)
                    || target.amount() < 1) {
                return false;
            }
            if (!target.useExisting()
                    && (target.preferredRecipeId() == null
                    || target.amount() > AutoCraftConfig.MAX_BATCHES_PER_TARGET.get())) {
                return false;
            }
            if (target.useExisting()) {
                materialItems = saturatedAdd(materialItems, target.amount());
                if (materialItems > AutoCraftConfig.MAX_MATERIAL_ITEMS.get()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    static boolean complete(ServerPlayer player, CraftPlan plan, SourceGroup sources) {
        // Nothing is reserved while the animation is playing. Revalidate the
        // complete transaction at commit time so cancellation and disconnects
        // cannot consume or duplicate ingredients.
        for (StackAmount requirement : plan.requirements()) {
            if (!sources.canExtract(requirement.stack(), requirement.amount())) {
                EmiCraftingChains.LOGGER.info(
                        "EMI craft chain changed before commit for {}: missing {}x{}",
                        player.getGameProfile().getName(),
                        BuiltInRegistries.ITEM.getKey(requirement.stack().getItem()),
                        requirement.amount()
                );
                player.displayClientMessage(Component.translatable("message.emi_crafting_chains.changed"), true);
                return false;
            }
        }
        if (sources.extractAll(plan.requirements()) != CommitResult.SUCCESS) {
            EmiCraftingChains.LOGGER.info(
                    "EMI craft chain extraction changed during commit for {}",
                    player.getGameProfile().getName()
            );
            player.displayClientMessage(Component.translatable("message.emi_crafting_chains.changed"), true);
            return false;
        }

        // Requested results go to the player. Container items and batch surplus
        // return to the open network first, then the player, then the ground.
        for (StackAmount delivery : plan.deliveries()) {
            sources.insertOrDrop(player, delivery.stack(), delivery.amount(), false);
        }
        for (StackAmount surplus : plan.surplus()) {
            sources.insertOrDrop(player, surplus.stack(), surplus.amount(), true);
        }

        awardCrafting(player, plan.craftedSteps());
        player.containerMenu.broadcastChanges();
        if (plan.deliveries().size() == 1) {
            StackAmount delivery = plan.deliveries().get(0);
            player.displayClientMessage(Component.translatable(
                    "message.emi_crafting_chains.success",
                    delivery.stack().getHoverName(),
                    delivery.amount()
            ), true);
        } else {
            long deliveredItems = plan.deliveries().stream()
                    .mapToLong(StackAmount::amount)
                    .reduce(0L, CraftService::saturatedAdd);
            player.displayClientMessage(Component.translatable(
                    "message.emi_crafting_chains.success_multiple",
                    deliveredItems,
                    plan.deliveries().size()
            ), true);
        }
        EmiCraftingChains.LOGGER.info(
                "Completed EMI craft chain for {}: {} delivery stacks",
                player.getGameProfile().getName(),
                plan.deliveries().size()
        );
        return true;
    }

    private static void awardCrafting(ServerPlayer player, List<CraftedStep> steps) {
        if (steps.isEmpty()) {
            return;
        }
        List<Recipe<?>> recipes = new ArrayList<>();
        for (CraftedStep step : steps) {
            Recipe<?> recipe = step.recipe();
            if (!recipes.contains(recipe)) {
                recipes.add(recipe);
            }
        }
        player.awardRecipes(recipes);
        for (CraftedStep step : steps) {
            ItemStack output = step.output().copy();
            output.onCraftedBy(player.level(), player, output.getCount());
        }
    }

    public record ChainAvailability(List<StackAmount> available, int maximumBatches) {
    }
}
