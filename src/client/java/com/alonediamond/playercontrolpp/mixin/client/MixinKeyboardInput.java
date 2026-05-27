package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput {

    @Inject(method = "tick", at = @At("TAIL"))
    private void playercontrolpp$afterTick(CallbackInfo ci) {
        boolean autoForward = AutoForwardFeature.isEnabled();
        boolean routeForward = RouteFlowRuntime.getInstance().isForwardActive();

        if (!autoForward && !routeForward) {
            return;
        }

        Input self = (Input) (Object) this;
        PlayerInput original = self.playerInput;
        self.playerInput = new PlayerInput(
                true,                    // forward = true
                false,                   // backward = false
                original.left(),         // preserve A
                original.right(),        // preserve D
                original.jump(),         // preserve jump
                original.sneak(),        // preserve sneak
                original.sprint()        // preserve sprint
        );
        self.movementForward = 1.0F;
    }
}
