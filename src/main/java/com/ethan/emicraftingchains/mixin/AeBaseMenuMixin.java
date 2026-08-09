package com.ethan.emicraftingchains.mixin;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.me.common.MEStorageMenu;
import com.ethan.emicraftingchains.compat.ae2.Ae2MultiblockCraftManager;
import com.ethan.emicraftingchains.compat.ae2.MultiblockPatternItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shift-clicking our pattern from player inventory requests it instead of storing it. */
@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class AeBaseMenuMixin {
    @Inject(method = "m_7648_", at = @At("HEAD"), cancellable = true)
    private void multiblockPatterns$requestPattern(
            Player player,
            int slotIndex,
            CallbackInfoReturnable<ItemStack> callback
    ) {
        AEBaseMenu menu = (AEBaseMenu) (Object) this;
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(menu instanceof MEStorageMenu terminal)
                || slotIndex < 0
                || slotIndex >= menu.slots.size()) {
            return;
        }
        Slot slot = menu.slots.get(slotIndex);
        boolean playerSlot = menu.getSlots(SlotSemantics.PLAYER_INVENTORY).contains(slot)
                || menu.getSlots(SlotSemantics.PLAYER_HOTBAR).contains(slot);
        ItemStack pattern = slot.getItem();
        if (!playerSlot || !MultiblockPatternItem.isMultiblockPattern(pattern)) {
            return;
        }
        Ae2MultiblockCraftManager.request(
                serverPlayer, terminal, MultiblockPatternItem.readMaterials(pattern));
        callback.setReturnValue(ItemStack.EMPTY);
    }
}
