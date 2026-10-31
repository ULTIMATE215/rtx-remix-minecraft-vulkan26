/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.FeatureCaptureFrameAccess;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class PreparedFeatureFrameMixin implements FeatureCaptureFrameAccess {
    @Shadow private FeatureFrameContext context;

    @Override
    public boolean rtxremix$isOpen() {
        return context != null;
    }

    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;endDraw()V"))
    private void rtxremix$leaveBorrowedBufferAlone(StagedVertexBuffer buffer, Operation<Void> original) {
        if (EntityRenderContext.get().vertexConsumers() == null) original.call(buffer);
    }
}
