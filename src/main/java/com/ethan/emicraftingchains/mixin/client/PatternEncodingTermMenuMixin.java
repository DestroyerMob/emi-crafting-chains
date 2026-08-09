package com.ethan.emicraftingchains.mixin.client;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.ethan.emicraftingchains.client.MultiblockPatternEncodingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PatternEncodingTermMenu.class, remap = false)
public abstract class PatternEncodingTermMenuMixin {
    @Inject(method = "encode", at = @At("HEAD"), cancellable = true)
    private void multiblockPatterns$encodeSelectedStructure(CallbackInfo callback) {
        if (MultiblockPatternEncodingClient.tryEncode((PatternEncodingTermMenu) (Object) this)) {
            callback.cancel();
        }
    }
}
