package WayFarMap.mixins.early;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft_Example {

    @Inject(method = "startGame", at = @At("TAIL"))
    private void example$sayHello(CallbackInfo ci) {
        System.out.println("WayFarMap says Hello from within Minecraft.startGame()!");
    }
}
