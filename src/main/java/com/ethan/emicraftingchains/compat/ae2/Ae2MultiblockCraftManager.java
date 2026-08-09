package com.ethan.emicraftingchains.compat.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.menu.me.common.MEStorageMenu;
import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.config.AutoCraftConfig;
import com.ethan.emicraftingchains.storage.StackAmount;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Sequentially submits standalone AE2 jobs for the missing parts of a
 * multiblock. Waiting for each job keeps calculations authoritative even when
 * several requested blocks share ingredients or the network has one CPU.
 */
public final class Ae2MultiblockCraftManager {
    private static final Map<UUID, RequestQueue> QUEUES = new LinkedHashMap<>();

    private Ae2MultiblockCraftManager() {
    }

    public static void request(
            ServerPlayer player,
            MEStorageMenu terminal,
            List<StackAmount> requested
    ) {
        if (QUEUES.containsKey(player.getUUID())) {
            player.displayClientMessage(Component.translatable(
                    "message.emi_crafting_chains.ae2_busy"), true);
            return;
        }
        List<StackAmount> materials = mergeAndValidate(requested);
        if (materials.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "message.emi_crafting_chains.ae2_empty"), true);
            return;
        }

        IGridNode node = terminal.getNetworkNode();
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null || !node.isActive()) {
            player.displayClientMessage(Component.translatable(
                    "message.emi_crafting_chains.ae2_offline"), true);
            return;
        }

        QUEUES.put(player.getUUID(), new RequestQueue(
                player.getUUID(), grid, terminal.getActionSource(), materials));
        player.displayClientMessage(Component.translatable(
                "message.emi_crafting_chains.ae2_queued", materials.size()), true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || QUEUES.isEmpty()) {
            return;
        }
        List<UUID> finished = new ArrayList<>();
        for (RequestQueue queue : List.copyOf(QUEUES.values())) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(queue.playerId);
            if (player == null || !queue.tick(player)) {
                finished.add(queue.playerId);
            }
        }
        for (UUID playerId : finished) {
            QUEUES.remove(playerId);
        }
    }

    private static List<StackAmount> mergeAndValidate(List<StackAmount> requested) {
        List<StackAmount> merged = new ArrayList<>();
        long total = 0;
        for (StackAmount entry : requested) {
            if (entry.stack().isEmpty() || entry.amount() <= 0 || entry.amount() > 1_000_000L) {
                return List.of();
            }
            total = saturatedAdd(total, entry.amount());
            if (total > AutoCraftConfig.MAX_MATERIAL_ITEMS.get()) {
                return List.of();
            }
            boolean found = false;
            for (int i = 0; i < merged.size(); i++) {
                StackAmount existing = merged.get(i);
                if (ItemStack.isSameItemSameTags(existing.stack(), entry.stack())) {
                    merged.set(i, new StackAmount(
                            existing.stack(), saturatedAdd(existing.amount(), entry.amount())));
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.add(entry);
            }
        }
        return List.copyOf(merged);
    }

    private static long saturatedAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static final class RequestQueue {
        private final UUID playerId;
        private final IGrid grid;
        private final IActionSource actionSource;
        private final List<StackAmount> materials;
        private int index;
        private Future<ICraftingPlan> calculation;
        private ICraftingLink link;

        private RequestQueue(
                UUID playerId,
                IGrid grid,
                IActionSource actionSource,
                List<StackAmount> materials
        ) {
            this.playerId = playerId;
            this.grid = grid;
            this.actionSource = actionSource;
            this.materials = materials;
        }

        private boolean tick(ServerPlayer player) {
            try {
                if (index >= materials.size()) {
                    player.displayClientMessage(Component.translatable(
                            "message.emi_crafting_chains.ae2_complete", materials.size()), true);
                    return false;
                }
                if (link != null) {
                    if (link.isCanceled()) {
                        return fail(player, "message.emi_crafting_chains.ae2_cancelled");
                    }
                    if (!link.isDone()) {
                        return true;
                    }
                    link = null;
                    index++;
                    return true;
                }
                if (calculation != null) {
                    if (!calculation.isDone()) {
                        return true;
                    }
                    ICraftingPlan plan = calculation.get();
                    calculation = null;
                    if (plan.simulation()) {
                        return fail(player, "message.emi_crafting_chains.ae2_missing_pattern");
                    }
                    ICraftingSubmitResult result = grid.getCraftingService().submitJob(
                            plan, null, null, true, actionSource);
                    if (!result.successful() || result.link() == null) {
                        EmiCraftingChains.LOGGER.info(
                                "AE2 multiblock job submission failed for {}: {}",
                                player.getGameProfile().getName(), result.errorCode());
                        return fail(player, "message.emi_crafting_chains.ae2_no_cpu");
                    }
                    link = result.link();
                    StackAmount material = materials.get(index);
                    player.displayClientMessage(Component.translatable(
                            "message.emi_crafting_chains.ae2_crafting",
                            material.stack().getHoverName(), index + 1, materials.size()), true);
                    return true;
                }

                StackAmount material = materials.get(index);
                AEItemKey key = AEItemKey.of(material.stack());
                if (key == null) {
                    return fail(player, "message.emi_crafting_chains.ae2_missing_pattern");
                }
                long available = grid.getStorageService().getCachedInventory().get(key);
                long missing = Math.max(0L, material.amount() - available);
                if (missing == 0) {
                    index++;
                    return true;
                }

                ICraftingService crafting = grid.getCraftingService();
                if (!crafting.isCraftable(key)) {
                    player.displayClientMessage(Component.translatable(
                            "message.emi_crafting_chains.ae2_uncraftable",
                            material.stack().getHoverName()), true);
                    return false;
                }
                ICraftingSimulationRequester requester = () -> actionSource;
                calculation = crafting.beginCraftingCalculation(
                        player.serverLevel(), requester, key, missing,
                        CalculationStrategy.REPORT_MISSING_ITEMS);
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return fail(player, "message.emi_crafting_chains.ae2_failed");
            } catch (ExecutionException | RuntimeException exception) {
                EmiCraftingChains.LOGGER.warn(
                        "AE2 multiblock crafting request failed for {}",
                        player.getGameProfile().getName(), exception);
                return fail(player, "message.emi_crafting_chains.ae2_failed");
            }
        }

        private boolean fail(ServerPlayer player, String translationKey) {
            player.displayClientMessage(Component.translatable(translationKey), true);
            return false;
        }
    }
}
