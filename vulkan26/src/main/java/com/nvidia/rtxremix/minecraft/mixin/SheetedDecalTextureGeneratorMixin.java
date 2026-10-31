/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserves vanilla's projection while closing captured vertices after their projected UVs. */
@Mixin(SheetedDecalTextureGenerator.class)
public abstract class SheetedDecalTextureGeneratorMixin {
    @Shadow @Final private VertexConsumer delegate;

    @WrapOperation(method = "setNormal", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setNormal(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer rtxremix$deferNormal(VertexConsumer consumer, float x, float y, float z,
                                               Operation<VertexConsumer> original) {
        return EntityRenderContext.get().isActive() ? consumer : original.call(consumer, x, y, z);
    }

    @Inject(method = "setNormal", at = @At("RETURN"))
    private void rtxremix$finishVertex(float x, float y, float z, CallbackInfoReturnable<VertexConsumer> cir) {
        if (EntityRenderContext.get().isActive()) delegate.setNormal(x, y, z);
    }
}
