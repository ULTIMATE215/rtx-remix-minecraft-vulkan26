/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Keeps first-person hand ownership alive until the queued arm and item geometry is captured. */
@Mixin(ItemInHandRenderer.class)
public abstract class HeldItemRendererMixin {
    @WrapMethod(method = "submitArmWithItem")
    private void rtxremix$captureHand(AbstractClientPlayer player, float tickDelta, float pitch,
                                     InteractionHand hand, float swingProgress, ItemStack stack,
                                     float equipProgress, PoseStack matrices, SubmitNodeCollector collector,
                                     int light, Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            original.call(player, tickDelta, pitch, hand, swingProgress, stack, equipProgress,
                    matrices, collector, light);
            return;
        }
        context.flushFeatures();
        InteractionHand previousHand = context.activeHand();
        LivingEntity previousHolder = context.getHeldItemHolder();
        context.setActiveHand(hand);
        context.setHeldItemHolder(player);
        try {
            original.call(player, tickDelta, pitch, hand, swingProgress, stack, equipProgress,
                    matrices, collector, light);
            context.flushFeatures();
        } finally {
            context.setHeldItemHolder(previousHolder);
            context.setActiveHand(previousHand);
        }
    }
}
