/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin {
    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureVertices(RenderType layer, CallbackInfoReturnable<VertexConsumer> cir) {
        var provider = EntityRenderContext.get().vertexConsumers();
        if (provider != null) cir.setReturnValue(provider.getBuffer(layer));
    }
}
