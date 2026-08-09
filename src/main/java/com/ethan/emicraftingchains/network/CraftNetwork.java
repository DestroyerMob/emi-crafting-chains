package com.ethan.emicraftingchains.network;

import com.ethan.emicraftingchains.EmiCraftingChains;
import com.ethan.emicraftingchains.client.ChainCraftHandler;
import com.ethan.emicraftingchains.client.CraftingGridAnimator;
import com.ethan.emicraftingchains.crafting.CraftService;
import com.ethan.emicraftingchains.crafting.CraftService.ChainAvailability;
import com.ethan.emicraftingchains.storage.StackAmount;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
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
    public static final int MAX_CHAIN_BATCHES = 64;
    public static final int MAX_AVAILABILITY_STACKS = 512;
    private static final int MAX_DISPLAY_STACK_COUNT = 1_000_000;
    private static final String PROTOCOL = "5";
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
    }

    public static void sendChainRequest(
            Item item,
            ResourceLocation rootRecipeId,
            List<ResourceLocation> chainRecipeIds,
            int batches
    ) {
        CHANNEL.sendToServer(new CraftRequest(
                BuiltInRegistries.ITEM.getKey(item),
                rootRecipeId,
                List.copyOf(chainRecipeIds),
                batches
        ));
    }

    public static void sendProgress(
            ServerPlayer player,
            List<ItemStack> inputs,
            ItemStack output,
            int step,
            int total
    ) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CraftProgress(inputs, output, step, total));
    }

    public static void requestAvailability(
            int requestId,
            Item item,
            ResourceLocation rootRecipeId,
            List<ResourceLocation> chainRecipeIds,
            int batches,
            List<ItemStack> queriedStacks
    ) {
        CHANNEL.sendToServer(new AvailabilityRequest(
                requestId,
                BuiltInRegistries.ITEM.getKey(item),
                rootRecipeId,
                List.copyOf(chainRecipeIds),
                batches,
                queriedStacks.stream().map(ItemStack::copy).toList()
        ));
    }

    private record CraftRequest(
            ResourceLocation itemId,
            ResourceLocation recipeId,
            List<ResourceLocation> chainRecipeIds,
            int batches
    ) {
        private static void encode(CraftRequest packet, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(packet.itemId());
            buffer.writeResourceLocation(packet.recipeId());
            buffer.writeVarInt(packet.chainRecipeIds().size());
            for (ResourceLocation recipeId : packet.chainRecipeIds()) {
                buffer.writeResourceLocation(recipeId);
            }
            buffer.writeVarInt(packet.batches());
        }

        private static CraftRequest decode(FriendlyByteBuf buffer) {
            ResourceLocation item = buffer.readResourceLocation();
            ResourceLocation recipe = buffer.readResourceLocation();
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_CHAIN_RECIPES) {
                throw new DecoderException("Invalid EMI recipe chain size: " + size);
            }
            List<ResourceLocation> chain = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                chain.add(buffer.readResourceLocation());
            }
            int batches = buffer.readVarInt();
            if (batches < 1 || batches > MAX_CHAIN_BATCHES) {
                throw new DecoderException("Invalid EMI recipe chain batch count: " + batches);
            }
            return new CraftRequest(item, recipe, chain, batches);
        }

        private static void handle(CraftRequest packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                EmiCraftingChains.LOGGER.info(
                        "Received EMI craft chain from {}: item={}, root={}, recipes={}, batches={}, menu={}",
                        player.getGameProfile().getName(),
                        packet.itemId(),
                        packet.recipeId(),
                        packet.chainRecipeIds().size(),
                        packet.batches(),
                        player.containerMenu.getClass().getName()
                );
                CraftService.craft(
                        player,
                        packet.itemId(),
                        packet.recipeId(),
                        packet.chainRecipeIds(),
                        packet.batches()
                );
            }
            context.setPacketHandled(true);
        }
    }

    private record CraftProgress(List<ItemStack> inputs, ItemStack output, int step, int total) {
        private static void encode(CraftProgress packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.inputs().size());
            for (ItemStack input : packet.inputs()) {
                writeDisplayStack(buffer, input);
            }
            writeDisplayStack(buffer, packet.output());
            buffer.writeVarInt(packet.step());
            buffer.writeVarInt(packet.total());
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
            if (step < 1 || total < 1 || step > total || total > MAX_CHAIN_RECIPES) {
                throw new DecoderException("Invalid EMI crafting-grid progress: " + step + "/" + total);
            }
            return new CraftProgress(inputs, output, step, total);
        }

        private static void handle(CraftProgress packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    CraftingGridAnimator.show(packet.inputs(), packet.output()));
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
            ResourceLocation itemId,
            ResourceLocation recipeId,
            List<ResourceLocation> chainRecipeIds,
            int batches,
            List<ItemStack> queriedStacks
    ) {
        private static void encode(AvailabilityRequest packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.requestId());
            buffer.writeResourceLocation(packet.itemId());
            buffer.writeResourceLocation(packet.recipeId());
            buffer.writeVarInt(packet.chainRecipeIds().size());
            for (ResourceLocation recipeId : packet.chainRecipeIds()) {
                buffer.writeResourceLocation(recipeId);
            }
            buffer.writeVarInt(packet.batches());
            buffer.writeVarInt(packet.queriedStacks().size());
            for (ItemStack stack : packet.queriedStacks()) {
                buffer.writeItem(stack);
            }
        }

        private static AvailabilityRequest decode(FriendlyByteBuf buffer) {
            int requestId = buffer.readVarInt();
            ResourceLocation item = buffer.readResourceLocation();
            ResourceLocation recipe = buffer.readResourceLocation();
            int chainSize = buffer.readVarInt();
            if (chainSize < 0 || chainSize > MAX_CHAIN_RECIPES) {
                throw new DecoderException("Invalid EMI availability chain size: " + chainSize);
            }
            List<ResourceLocation> chain = new ArrayList<>(chainSize);
            for (int i = 0; i < chainSize; i++) {
                chain.add(buffer.readResourceLocation());
            }
            int batches = buffer.readVarInt();
            if (batches < 1 || batches > MAX_CHAIN_BATCHES) {
                throw new DecoderException("Invalid EMI availability batch count: " + batches);
            }
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
            return new AvailabilityRequest(requestId, item, recipe, chain, batches, queries);
        }

        private static void handle(AvailabilityRequest packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                ChainAvailability availability = CraftService.analyze(
                        player,
                        packet.itemId(),
                        packet.recipeId(),
                        packet.chainRecipeIds(),
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
