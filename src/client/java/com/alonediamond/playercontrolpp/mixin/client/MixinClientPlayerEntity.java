package com.alonediamond.playercontrolpp.mixin.client;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    @Shadow
    public net.minecraft.client.input.Input input;

    @Inject(method = "tick", at = @At("HEAD"))
    private void playercontrolpp$onTick(CallbackInfo ci) {
        if (!AutoForwardFeature.isEnabled()) {
            return;
        }

        PlayerInput original = this.input.playerInput;
        this.input.playerInput = new PlayerInput(
                true,                    // forward = true
                false,                   // backward = false
                original.left(),         // preserve A
                original.right(),        // preserve D
                original.jump(),         // preserve jump
                original.sneak(),        // preserve sneak
                original.sprint()        // preserve sprint
        );
        this.input.movementForward = 1.0F;
    }
}
