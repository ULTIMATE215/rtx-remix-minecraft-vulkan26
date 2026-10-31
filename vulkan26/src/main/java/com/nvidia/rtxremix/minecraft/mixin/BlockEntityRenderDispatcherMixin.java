/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.BlockEntityRenderStateAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private void rtxremix$rememberOwner(BlockEntity blockEntity, float tickDelta,
                                      ModelFeatureRenderer.CrumblingOverlay breakProgress,
                                      boolean ignoreDistance,
                                      CallbackInfoReturnable<BlockEntityRenderState> cir) {
        BlockEntityRenderState state = cir.getReturnValue();
        if (state != null) {
            ((BlockEntityRenderStateAccess) state).rtxremix$setBlockEntity(blockEntity);
        }
    }
}
