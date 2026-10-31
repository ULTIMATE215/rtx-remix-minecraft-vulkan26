/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Carries ownership through extraction and omits raster blob shadows during capture. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void rtxremix$rememberOwner(Entity entity, float tickDelta,
                                      CallbackInfoReturnable<EntityRenderState> cir) {
        ((EntityRenderStateAccess) cir.getReturnValue()).rtxremix$setEntity(entity, tickDelta);
    }

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"))
    private void rtxremix$omitBlobShadow(SubmitNodeCollector collector, PoseStack matrices,
                                       float radius, List<EntityRenderState.ShadowPiece> pieces,
                                       Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass() && !context.suppressesVanillaShadows()) {
            original.call(collector, matrices, radius, pieces);
        }
    }
}
