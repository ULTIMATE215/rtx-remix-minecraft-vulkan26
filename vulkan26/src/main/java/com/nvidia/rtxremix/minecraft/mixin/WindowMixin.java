/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the window while leaving backend-specific hints to Minecraft. */
@Mixin(Window.class)
public abstract class WindowMixin {
    
    /** Logs the GLFW window handle once the window exists. */
    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void rtxremix$onWindowCreated(CallbackInfo ci) {
        RtxRemixMod.LOGGER.info("Window created with GLFW handle: {}", ((Window) (Object) this).handle());
    }
}
