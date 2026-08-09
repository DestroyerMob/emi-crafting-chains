package com.ethan.emicraftingchains.storage;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ItemSources {
    private ItemSources() {
    }

    public static SourceGroup forPlayer(ServerPlayer player) {
        List<ItemSource> sources = new ArrayList<>();
        sources.add(new PlayerItemSource(player));

        ItemSource external = null;
        if (ModList.get().isLoaded("ae2")) {
            external = load("com.ethan.emicraftingchains.compat.ae2.Ae2ItemSource", player);
        }
        if (external == null && ModList.get().isLoaded("toms_storage")) {
            external = load("com.ethan.emicraftingchains.compat.toms.TomsItemSource", player);
        }
        if (external != null) {
            sources.add(external);
        }
        return new SourceGroup(sources, external);
    }

    private static ItemSource load(String className, ServerPlayer player) {
        try {
            Class<?> type = Class.forName(className);
            Method create = type.getMethod("create", ServerPlayer.class);
            return (ItemSource) create.invoke(null, player);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    public static final class SourceGroup {
        private final List<ItemSource> sources;
        private final ItemSource external;

        private SourceGroup(List<ItemSource> sources, ItemSource external) {
            this.sources = List.copyOf(sources);
            this.external = external;
        }

        public List<StackAmount> snapshot() {
            List<StackAmount> result = new ArrayList<>();
            for (ItemSource source : sources) {
                result.addAll(source.snapshot());
            }
            return result;
        }

        public boolean canExtract(ItemStack stack, long amount) {
            long remaining = amount;
            for (ItemSource source : sources) {
                remaining -= source.extract(stack, remaining, true);
                if (remaining <= 0) {
                    return true;
                }
            }
            return false;
        }

        public CommitResult extractAll(List<StackAmount> requirements) {
            List<Extraction> completed = new ArrayList<>();
            for (StackAmount requirement : requirements) {
                long remaining = requirement.amount();
                for (ItemSource source : sources) {
                    if (remaining <= 0) {
                        break;
                    }
                    long extracted = source.extract(requirement.stack(), remaining, false);
                    if (extracted > 0) {
                        completed.add(new Extraction(source, requirement.stack(), extracted));
                        remaining -= extracted;
                    }
                }
                if (remaining > 0) {
                    refund(completed);
                    return CommitResult.CHANGED;
                }
            }
            return CommitResult.SUCCESS;
        }

        public void insertOrDrop(ServerPlayer player, ItemStack template, long amount, boolean preferExternal) {
            long remaining = amount;
            if (preferExternal && external != null) {
                remaining -= external.insert(template, remaining, false);
            }
            for (ItemSource source : sources) {
                if (remaining <= 0 || source == external) {
                    continue;
                }
                remaining -= source.insert(template, remaining, false);
            }
            while (remaining > 0) {
                ItemStack dropped = template.copy();
                dropped.setCount((int) Math.min(remaining, dropped.getMaxStackSize()));
                player.drop(dropped, false);
                remaining -= dropped.getCount();
            }
        }

        private void refund(List<Extraction> extractions) {
            for (int i = extractions.size() - 1; i >= 0; i--) {
                Extraction extraction = extractions.get(i);
                long inserted = extraction.source().insert(extraction.stack(), extraction.amount(), false);
                long remaining = extraction.amount() - inserted;
                for (ItemSource source : sources) {
                    if (remaining <= 0 || source == extraction.source()) {
                        continue;
                    }
                    remaining -= source.insert(extraction.stack(), remaining, false);
                }
            }
        }

        private record Extraction(ItemSource source, ItemStack stack, long amount) {
        }
    }

    public enum CommitResult {
        SUCCESS,
        CHANGED
    }
}
