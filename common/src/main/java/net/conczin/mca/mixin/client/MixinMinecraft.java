package net.conczin.mca.mixin.client;

import net.conczin.mca.client.gui.BlueprintScreen;
import net.conczin.mca.item.BlueprintItem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void mca$openBlueprintCommand(CallbackInfoReturnable<Boolean> callback) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.player != null && client.player.getMainHandItem().getItem() instanceof BlueprintItem) {
            if (client.gui.screen() == null) client.gui.setScreen(new BlueprintScreen(true));
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void mca$preventBlueprintBlockBreaking(boolean attacking, CallbackInfo callback) {
        Minecraft client = (Minecraft) (Object) this;
        if (client.player != null && client.player.getMainHandItem().getItem() instanceof BlueprintItem) callback.cancel();
    }
}
