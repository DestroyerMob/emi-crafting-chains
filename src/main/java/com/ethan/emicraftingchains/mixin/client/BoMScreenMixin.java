package com.ethan.emicraftingchains.mixin.client;

import com.ethan.emicraftingchains.client.ChainCraftHandler;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Supplies EMI's native crafting-mode progress calculation with the terminal
 * inventory synchronized by the server. */
@Mixin(value = BoMScreen.class, remap = false)
public abstract class BoMScreenMixin {
    @Redirect(
            method = "recalculateTree",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/emi/emi/api/recipe/EmiPlayerInventory;of(Lnet/minecraft/world/entity/player/Player;)Ldev/emi/emi/api/recipe/EmiPlayerInventory;"
            )
    )
    private EmiPlayerInventory emiAutoCraft$useTerminalInventory(Player player) {
        return ChainCraftHandler.getSyncedInventory(player);
    }
}
