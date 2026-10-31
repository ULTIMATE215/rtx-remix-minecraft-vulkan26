/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.ItemRenderStateAccess;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemRenderStateAccess {
    @Unique private ItemStack rtxremix$stack = ItemStack.EMPTY;
    @Unique private LivingEntity rtxremix$holder;
    @Unique private int rtxremix$blockLayer = EntityRenderContext.NO_BLOCK_ITEM;
    @Unique private boolean rtxremix$heldEffectsEmitted;

    @Override
    public void rtxremix$setSource(ItemStack stack, LivingEntity holder) {
        rtxremix$stack = stack;
        rtxremix$holder = holder;
        rtxremix$blockLayer = stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(
                        BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }

    @Override public ItemStack rtxremix$stack() { return rtxremix$stack; }

    @Override
    public boolean rtxremix$claimHeldEffects() {
        if (rtxremix$heldEffectsEmitted) return false;
        rtxremix$heldEffectsEmitted = true;
        return true;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clearSource(CallbackInfo ci) {
        rtxremix$stack = ItemStack.EMPTY;
        rtxremix$holder = null;
        rtxremix$blockLayer = EntityRenderContext.NO_BLOCK_ITEM;
        rtxremix$heldEffectsEmitted = false;
    }

    @WrapMethod(method = "submit")
    private void rtxremix$captureItemScope(PoseStack matrices, SubmitNodeCollector collector,
                                         int light, int overlay, int outlineColor,
                                         Operation<Void> original) {
        EntityRenderContext context = EntityRenderContext.get();
        if (!context.isRemixPass()) {
            original.call(matrices, collector, light, overlay, outlineColor);
            return;
        }
        context.flushFeatures();
        LivingEntity previousHolder = context.getHeldItemHolder();
        int previousLayer = context.getBlockItemLayer();
        ItemRenderStateAccess previousItem = context.itemRenderState();
        context.setHeldItemHolder(rtxremix$holder);
        context.setBlockItemLayer(rtxremix$blockLayer);
        context.setItemRenderState(this);
        rtxremix$heldEffectsEmitted = false;
        try {
            original.call(matrices, collector, light, overlay, outlineColor);
            context.flushFeatures();
        } finally {
            context.setItemRenderState(previousItem);
            context.setBlockItemLayer(previousLayer);
            context.setHeldItemHolder(previousHolder);
        }
    }
}
