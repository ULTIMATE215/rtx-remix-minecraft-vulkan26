/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.entity.BlockEntityRenderStateAccess;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderStateAccess;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures the states admitted by vanilla's world traversal before their raster submission. */
@Mixin(LevelRenderer.class)
public abstract class WorldEntityCaptureMixin {
    @WrapOperation(method = "submitEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void rtxremix$captureEntity(EntityRenderDispatcher dispatcher, EntityRenderState state,
                                      CameraRenderState camera, double x, double y, double z,
                                      PoseStack matrices, SubmitNodeCollector collector,
                                      Operation<Void> original,
                                      @Local(argsOnly = true) LevelRenderState levelState) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        EntityRenderStateAccess owner = (EntityRenderStateAccess) state;
        boolean captured = renderer != null && owner.rtxremix$entity() != null
                && renderer.renderEntityInPlace(owner.rtxremix$entity(), state, camera,
                        owner.rtxremix$tickDelta());
        if (!captured) {
            original.call(dispatcher, state, camera, x, y, z, matrices, collector);
        } else if (levelState.shouldShowEntityOutlines && state.appearsGlowing()) {
            // Minecraft still owns the outline target. Its second submission must
            // not add the blob shadow to the image composited over Remix.
            EntityRenderContext context = EntityRenderContext.get();
            boolean suppressed = context.suppressesVanillaShadows();
            context.setSuppressVanillaShadows(true);
            try {
                original.call(dispatcher, state, camera, x, y, z, matrices, collector);
            } finally {
                context.setSuppressVanillaShadows(suppressed);
            }
        }
    }

    @WrapOperation(method = "submitBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private void rtxremix$captureBlockEntity(BlockEntityRenderDispatcher dispatcher,
                                           BlockEntityRenderState state, PoseStack matrices,
                                           SubmitNodeCollector collector, CameraRenderState camera,
                                           Operation<Void> original) {
        RtxRemixMod mod = RtxRemixMod.getInstance();
        RemixRenderer renderer = mod == null ? null : mod.getRenderer();
        BlockEntity blockEntity = ((BlockEntityRenderStateAccess) state).rtxremix$blockEntity();
        boolean captured = renderer != null && blockEntity != null
                && renderer.renderBlockEntityInPlace(blockEntity,
                        (captureMatrices, captureCollector) ->
                                original.call(dispatcher, state, captureMatrices, captureCollector, camera));
        if (!captured) {
            original.call(dispatcher, state, matrices, collector, camera);
        }
    }
}
