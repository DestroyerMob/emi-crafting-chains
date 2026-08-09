package com.ethan.emicraftingchains.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class AutoCraftConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue ANIMATE_STEPS;
    public static final ForgeConfigSpec.IntValue STEP_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_BATCHES_PER_TARGET;
    public static final ForgeConfigSpec.IntValue MAX_CRAFTING_OPERATIONS;
    public static final ForgeConfigSpec.IntValue MAX_MATERIAL_ITEMS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("autocrafting");
        ENABLED = builder
                .comment("Allows players to execute local Craft Chain requests.")
                .define("enabled", true);
        ANIMATE_STEPS = builder
                .comment("Shows each compacted crafting step before committing the atomic craft.")
                .define("animateSteps", true);
        STEP_TICKS = builder
                .comment("Delay per displayed crafting step. 20 ticks is one second.")
                .defineInRange("stepTicks", 10, 1, 200);
        MAX_BATCHES_PER_TARGET = builder
                .comment("Maximum recipe batches accepted for each tracked root item.")
                .defineInRange("maxBatchesPerTarget", 64, 1, 256);
        MAX_CRAFTING_OPERATIONS = builder
                .comment("Maximum individual crafting-table operations in one atomic chain.")
                .defineInRange("maxCraftingOperations", 512, 1, 2048);
        MAX_MATERIAL_ITEMS = builder
                .comment("Maximum total counted items in a local or AE2 multiblock request.")
                .defineInRange("maxMaterialItems", 32768, 64, 1000000);
        builder.pop();
        SPEC = builder.build();
    }

    private AutoCraftConfig() {
    }
}
