package com.ethan.emicraftingchains.network;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.client.ChainCraftHandler;
import com.ethan.emicraftingchains.client.CraftingGridAnimator;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternData;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternEncoder;
import com.ethan.emicraftingchains.crafting.CraftService;
import com.ethan.emicraftingchains.crafting.CraftService.ChainAvailability;
import com.ethan.emicraftingchains.crafting.CraftTarget;
import com.ethan.emicraftingchains.storage.StackAmount;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class CraftNetwork {
    public static final int MAX_CHAIN_RECIPES = 512;
    public static final int MAX_CHAIN_BATCHES = 256;
    public static final int MAX_CHAIN_TARGETS = 512;
    public static final int MAX_AVAILABILITY_STACKS = 512;
    public static final int MAX_TOTAL_RECIPE_IDS = 2_048;
    public static final int MAX_SUPPLIED_ONLY_STACKS = 512;
    public static final int MAX_TOTAL_SUPPLIED_ONLY_STACKS = 2_048;
    public static final int MAX_AE2_MATERIALS = 512;
    private static final int MAX_TARGET_AMOUNT = 1_000_000;
    private static final int MAX_CRAFTING_OPERATIONS = 2_048;
    private static final int MAX_DISPLAY_STACK_COUNT = 1_000_000;
    private static final String PROTOCOL = "9";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(EmiCraftingChains.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private CraftNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(CraftRequest.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CraftRequest::encode)
                .decoder(CraftRequest::decode)
                .consumerMainThread(CraftRequest::handle)
                .add();
        CHANNEL.messageBuilder(CraftProgress.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CraftProgress::encode)
                .decoder(CraftProgress::decode)
                .consumerMainThread(CraftProgress::handle)
                .add();
        CHANNEL.messageBuilder(AvailabilityRequest.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AvailabilityRequest::encode)
                .decoder(AvailabilityRequest::decode)
                .consumerMainThread(AvailabilityRequest::handle)
                .add();
        CHANNEL.messageBuilder(AvailabilityResponse.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AvailabilityResponse::encode)
                .decoder(AvailabilityResponse::decode)
                .consumerMainThread(AvailabilityResponse::handle)
                .add();
        CHANNEL.messageBuilder(MultiblockPatternEncode.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MultiblockPatternEncode::encode)
                .decoder(MultiblockPatternEncode::decode)
                .consumerMainThread(MultiblockPatternEncode::handle)
                .add();
    }

    public static void sendChainRequest(List<CraftTarget> targets) {
        CHANNEL.sendToServer(new CraftRequest(List.copyOf(targets)));
    }

    public static void sendProgress(
            ServerPlayer player,
            List<ItemStack> inputs,
            ItemStack output,
            int step,
            int total,
            int durationTicks
    ) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CraftProgress(inputs, output, step, total, durationTicks));
    }

    public static void requestAvailability(
            int requestId,
            List<CraftTarget> targets,
            List<ItemStack> queriedStacks
    ) {
        CHANNEL.sendToServer(new AvailabilityRequest(
                requestId,
                List.copyOf(targets),
                queriedStacks.stream().map(ItemStack::copy).toList()
        ));
    }

    public static void sendMultiblockPatternEncode(MultiblockPatternData data) {
        CHANNEL.sendToServer(new MultiblockPatternEncode(data));
    }

    private static void writeTargets(FriendlyByteBuf buffer, List<CraftTarget> targets) {
        buffer.writeVarInt(targets.size());
        for (CraftTarget target : targets) {
            buffer.writeItem(target.stack());
            buffer.writeBoolean(target.preferredRecipeId() != null);
            if (target.preferredRecipeId() != null) {
                buffer.writeResourceLocation(target.preferredRecipeId());
            }
            buffer.writeVarInt(target.chainRecipeIds().size());
            for (ResourceLocation recipeId : target.chainRecipeIds()) {
                buffer.writeResourceLocation(recipeId);
            }
            buffer.writeVarInt(target.suppliedOnly().size());
            for (ItemStack supplied : target.suppliedOnly()) {
                buffer.writeItem(supplied);
            }
            buffer.writeVarInt(target.amount());
            buffer.writeBoolean(target.useExisting());
        }
    }

    private static List<CraftTarget> readTargets(FriendlyByteBuf buffer) {
        int targetCount = buffer.readVarInt();
        if (targetCount < 1 || targetCount > MAX_CHAIN_TARGETS) {
            throw new DecoderException("Invalid EMI craft target count: " + targetCount);
        }

        List<CraftTarget> targets = new ArrayList<>(targetCount);
        int totalRecipeIds = 0;
        int totalSuppliedOnly = 0;
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            ItemStack stack = buffer.readItem();
            if (stack.isEmpty()) {
                throw new DecoderException("Empty EMI craft target");
            }
            stack.setCount(1);
            ResourceLocation preferredRecipe = buffer.readBoolean() ? buffer.readResourceLocation() : null;
            int recipeCount = buffer.readVarInt();
            totalRecipeIds += recipeCount;
            if (recipeCount < 0 || recipeCount > MAX_CHAIN_RECIPES || totalRecipeIds > MAX_TOTAL_RECIPE_IDS) {
                throw new DecoderException("Invalid EMI recipe chain size: " + recipeCount);
            }
            List<ResourceLocation> recipes = new ArrayList<>(recipeCount);
            for (int recipeIndex = 0; recipeIndex < recipeCount; recipeIndex++) {
                recipes.add(buffer.readResourceLocation());
            }
            int suppliedCount = buffer.readVarInt();
            totalSuppliedOnly += suppliedCount;
            if (suppliedCount < 0 || suppliedCount > MAX_SUPPLIED_ONLY_STACKS
                    || totalSuppliedOnly > MAX_TOTAL_SUPPLIED_ONLY_STACKS) {
                throw new DecoderException("Invalid supplied-only stack count: " + suppliedCount);
            }
            List<ItemStack> suppliedOnly = new ArrayList<>(suppliedCount);
            for (int suppliedIndex = 0; suppliedIndex < suppliedCount; suppliedIndex++) {
                ItemStack supplied = buffer.readItem();
                if (!supplied.isEmpty()) {
                    supplied.setCount(1);
                    suppliedOnly.add(supplied);
                }
            }
            int amount = buffer.readVarInt();
            boolean useExisting = buffer.readBoolean();
            int maximum = useExisting ? MAX_TARGET_AMOUNT : MAX_CHAIN_BATCHES;
            if (amount < 1 || amount > maximum || (!useExisting && preferredRecipe == null)) {
                throw new DecoderException("Invalid EMI craft target amount: " + amount);
            }
            targets.add(new CraftTarget(stack, preferredRecipe, recipes, suppliedOnly, amount, useExisting));
        }
        return List.copyOf(targets);
    }

    private record CraftRequest(List<CraftTarget> targets) {
        private static void encode(CraftRequest packet, FriendlyByteBuf buffer) {
            writeTargets(buffer, packet.targets());
        }

        private static CraftRequest decode(FriendlyByteBuf buffer) {
            return new CraftRequest(readTargets(buffer));
        }

        private static void handle(CraftRequest packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                EmiCraftingChains.LOGGER.info(
                        "Received EMI craft chain from {}: targets={}, menu={}",
                        player.getGameProfile().getName(),
                        packet.targets().size(),
                        player.containerMenu.getClass().getName()
                );
                CraftService.craft(player, packet.targets());
            }
            context.setPacketHandled(true);
        }
    }

    private record MultiblockPatternEncode(MultiblockPatternData data) {
        private static void encode(MultiblockPatternEncode packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.data().root());
            buffer.writeBoolean(packet.data().recipeId() != null);
            if (packet.data().recipeId() != null) {
                buffer.writeResourceLocation(packet.data().recipeId());
            }
            buffer.writeVarInt(packet.data().materials().size());
            for (StackAmount material : packet.data().materials()) {
                buffer.writeItem(material.stack());
                buffer.writeVarLong(material.amount());
            }
        }

        private static MultiblockPatternEncode decode(FriendlyByteBuf buffer) {
            ItemStack root = buffer.readItem();
            if (root.isEmpty()) {
                throw new DecoderException("Empty multiblock pattern structure");
            }
            root.setCount(1);
            ResourceLocation recipeId = buffer.readBoolean() ? buffer.readResourceLocation() : null;
            int size = buffer.readVarInt();
            if (size < 1 || size > MAX_AE2_MATERIALS) {
                throw new DecoderException("Invalid multiblock pattern material count: " + size);
            }
            List<StackAmount> materials = new ArrayList<>(size);
            long total = 0L;
            for (int i = 0; i < size; i++) {
                ItemStack stack = buffer.readItem();
                long amount = buffer.readVarLong();
                total = total > Long.MAX_VALUE - amount ? Long.MAX_VALUE : total + amount;
                if (stack.isEmpty() || amount < 1 || amount > MAX_TARGET_AMOUNT
                        || total > MAX_TARGET_AMOUNT) {
                    throw new DecoderException("Invalid multiblock pattern material amount: " + amount);
                }
                stack.setCount(1);
                materials.add(new StackAmount(stack, amount));
            }
            return new MultiblockPatternEncode(
                    new MultiblockPatternData(root, recipeId, List.copyOf(materials)));
        }

        private static void handle(
                MultiblockPatternEncode packet,
                Supplier<NetworkEvent.Context> contextSupplier
        ) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                MultiblockPatternEncoder.encode(player, packet.data());
            }
            context.setPacketHandled(true);
        }
    }

    private record CraftProgress(
            List<ItemStack> inputs,
            ItemStack output,
            int step,
            int total,
            int durationTicks
    ) {
        private static void encode(CraftProgress packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.inputs().size());
            for (ItemStack input : packet.inputs()) {
                writeDisplayStack(buffer, input);
            }
            writeDisplayStack(buffer, packet.output());
            buffer.writeVarInt(packet.step());
            buffer.writeVarInt(packet.total());
            buffer.writeVarInt(packet.durationTicks());
        }

        private static CraftProgress decode(FriendlyByteBuf buffer) {
            int size = buffer.readVarInt();
            if (size < 0 || size > 9) {
                throw new DecoderException("Invalid EMI crafting-grid input count: " + size);
            }
            List<ItemStack> inputs = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                inputs.add(readDisplayStack(buffer));
            }
            ItemStack output = readDisplayStack(buffer);
            int step = buffer.readVarInt();
            int total = buffer.readVarInt();
            int durationTicks = buffer.readVarInt();
            if (step < 1 || total < 1 || step > total || total > MAX_CRAFTING_OPERATIONS
                    || durationTicks < 1 || durationTicks > 200) {
                throw new DecoderException("Invalid EMI crafting-grid progress: " + step + "/" + total);
            }
            return new CraftProgress(inputs, output, step, total, durationTicks);
        }

        private static void handle(CraftProgress packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CraftingGridAnimator.show(
                    packet.inputs(), packet.output(), packet.durationTicks()));
            context.setPacketHandled(true);
        }

        private static void writeDisplayStack(FriendlyByteBuf buffer, ItemStack stack) {
            int count = stack.isEmpty() ? 0 : Math.min(stack.getCount(), MAX_DISPLAY_STACK_COUNT);
            ItemStack template = stack.copy();
            if (!template.isEmpty()) {
                template.setCount(1);
            }
            buffer.writeItem(template);
            buffer.writeVarInt(count);
        }

        private static ItemStack readDisplayStack(FriendlyByteBuf buffer) {
            ItemStack stack = buffer.readItem();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_DISPLAY_STACK_COUNT || (stack.isEmpty() && count != 0)) {
                throw new DecoderException("Invalid EMI crafting-grid stack count: " + count);
            }
            if (!stack.isEmpty()) {
                stack.setCount(count);
            }
            return stack;
        }
    }

    private record AvailabilityRequest(
            int requestId,
            List<CraftTarget> targets,
            List<ItemStack> queriedStacks
    ) {
        private static void encode(AvailabilityRequest packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.requestId());
            writeTargets(buffer, packet.targets());
            buffer.writeVarInt(packet.queriedStacks().size());
            for (ItemStack stack : packet.queriedStacks()) {
                buffer.writeItem(stack);
            }
        }

        private static AvailabilityRequest decode(FriendlyByteBuf buffer) {
            int requestId = buffer.readVarInt();
            List<CraftTarget> targets = readTargets(buffer);
            int querySize = buffer.readVarInt();
            if (querySize < 0 || querySize > MAX_AVAILABILITY_STACKS) {
                throw new DecoderException("Invalid EMI availability stack count: " + querySize);
            }
            List<ItemStack> queries = new ArrayList<>(querySize);
            for (int i = 0; i < querySize; i++) {
                ItemStack stack = buffer.readItem();
                if (!stack.isEmpty()) {
                    stack.setCount(1);
                    queries.add(stack);
                }
            }
            return new AvailabilityRequest(requestId, targets, queries);
        }

        private static void handle(AvailabilityRequest packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                ChainAvailability availability = CraftService.analyze(
                        player,
                        packet.targets(),
                        packet.queriedStacks(),
                        MAX_CHAIN_BATCHES
                );
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AvailabilityResponse(
                        packet.requestId(),
                        availability.maximumBatches(),
                        availability.available()
                ));
            }
            context.setPacketHandled(true);
        }
    }

    private record AvailabilityResponse(
            int requestId,
            int maximumBatches,
            List<StackAmount> available
    ) {
        private static void encode(AvailabilityResponse packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.requestId());
            buffer.writeVarInt(packet.maximumBatches());
            buffer.writeVarInt(packet.available().size());
            for (StackAmount available : packet.available()) {
                buffer.writeItem(available.stack());
                buffer.writeVarLong(available.amount());
            }
        }

        private static AvailabilityResponse decode(FriendlyByteBuf buffer) {
            int requestId = buffer.readVarInt();
            int maximumBatches = buffer.readVarInt();
            if (maximumBatches < 0 || maximumBatches > MAX_CHAIN_BATCHES) {
                throw new DecoderException("Invalid maximum EMI chain batch count: " + maximumBatches);
            }
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_AVAILABILITY_STACKS) {
                throw new DecoderException("Invalid EMI availability response size: " + size);
            }
            List<StackAmount> available = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                ItemStack stack = buffer.readItem();
                long amount = buffer.readVarLong();
                if (!stack.isEmpty() && amount > 0) {
                    available.add(new StackAmount(stack, amount));
                }
            }
            return new AvailabilityResponse(requestId, maximumBatches, available);
        }

        private static void handle(AvailabilityResponse packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ChainCraftHandler.acceptAvailability(
                    packet.requestId(),
                    packet.maximumBatches(),
                    packet.available()
            ));
            context.setPacketHandled(true);
        }
    }
}
