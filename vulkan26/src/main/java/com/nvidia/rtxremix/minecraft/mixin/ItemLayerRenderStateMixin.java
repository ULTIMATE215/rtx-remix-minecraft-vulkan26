/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.ItemRenderStateAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ItemLayerRenderStateMixin {
    @Shadow private ItemTransform itemTransform;

    @WrapOperation(method = "submit", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"))
    private void rtxremix$emitHeldEffects(SubmitNodeCollector collector, PoseStack matrices,
                                         ItemDisplayContext mode, int light, int overlay,
                                         int outlineColor, int[] tints, List<BakedQuad> quads,
                                         ItemStackRenderState.FoilType foil, Operation<Void> original) {
        original.call(collector, matrices, mode, light, overlay, outlineColor, tints, quads, foil);
        EntityRenderContext context = EntityRenderContext.get();
        ItemRenderStateAccess item = context.itemRenderState();
        if (!context.isRemixPass() || item == null || !item.rtxremix$claimHeldEffects()) return;
        // The layer's model transform is still on the stack. Drain its item
        // geometry before adding effects, and before that metadata is restored.
        context.flushFeatures();
        var consumers = context.captureConsumers();
        if (consumers != null) {
            HeldItemEffectsRenderer.render(item.rtxremix$stack(), mode, matrices, consumers,
                    itemTransform.scale().x());
        }
    }
}
