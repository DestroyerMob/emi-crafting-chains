package com.ethan.emicraftingchains.crafting;

import com.ethan.emicraftingchains.crafting.CraftPlan.CraftedStep;
import com.ethan.emicraftingchains.config.AutoCraftConfig;
import com.ethan.emicraftingchains.network.CraftNetwork;
import com.ethan.emicraftingchains.storage.ItemSources.SourceGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class CraftJobManager {
    private static final Map<UUID, CraftJob> JOBS = new HashMap<>();

    private CraftJobManager() {
    }

    public static boolean isBusy(ServerPlayer player) {
        return JOBS.containsKey(player.getUUID());
    }

    public static void start(ServerPlayer player, CraftPlan plan, SourceGroup sources) {
        if (plan.visualSteps().isEmpty() || !AutoCraftConfig.ANIMATE_STEPS.get()) {
            CraftService.complete(player, plan, sources);
            return;
        }

        CraftJob job = new CraftJob(player.containerMenu, plan, sources);
        JOBS.put(player.getUUID(), job);
        showStep(player, job);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || JOBS.isEmpty()) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }

        Iterator<Map.Entry<UUID, CraftJob>> iterator = JOBS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CraftJob> entry = iterator.next();
            CraftJob job = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (player.containerMenu != job.startingMenu) {
                iterator.remove();
                player.displayClientMessage(Component.translatable("message.emi_crafting_chains.cancelled"), true);
                continue;
            }
            if (--job.ticksRemaining > 0) {
                continue;
            }

            job.stepIndex++;
            if (job.stepIndex < job.plan.visualSteps().size()) {
                job.ticksRemaining = stepTicks();
                showStep(player, job);
            } else {
                iterator.remove();
                CraftService.complete(player, job.plan, job.sources);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        JOBS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        JOBS.clear();
    }

    private static void showStep(ServerPlayer player, CraftJob job) {
        CraftedStep step = job.plan.visualSteps().get(job.stepIndex);
        int number = job.stepIndex + 1;
        int total = job.plan.visualSteps().size();
        CraftNetwork.sendProgress(player, step.inputs(), step.output(), number, total, stepTicks());
    }

    private static int stepTicks() {
        return AutoCraftConfig.STEP_TICKS.get();
    }

    private static final class CraftJob {
        private final AbstractContainerMenu startingMenu;
        private final CraftPlan plan;
        private final SourceGroup sources;
        private int stepIndex;
        private int ticksRemaining = stepTicks();

        private CraftJob(AbstractContainerMenu startingMenu, CraftPlan plan, SourceGroup sources) {
            this.startingMenu = startingMenu;
            this.plan = plan;
            this.sources = sources;
        }
    }
}
