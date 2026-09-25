/* Copyright (C) 2026 Gr1mly4Memes.
 * SPDX-License-Identifier: GPL-3.0-only */
package gr1mly4memes.lumance.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Example built-in mixin. Put Lumance's own mixins next to this one and list
 * them in {@code lumance.mixins.json}; they ship inside Lumance and apply on
 * every boot, no separate mod jar needed.
 */
@Mixin(MinecraftServer.class)
public class ExampleMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void lumance$onRunServer(CallbackInfo ci) {
        System.out.println("Lumance mixin active");
    }
}
