/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps extraction, scene capture, first-person geometry, and compositing in frame order. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Unique
    private ProfileScope rtxremix$guiScope;

    /** Input capture must be resolved before the GUI extracts its hover state. */
    @Inject(method = "update", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(DeltaTracker delta, CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$prepareResources(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        RtxRemixMod.prepareWorldRenderResources(renderLevel);
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$beginWorld(DeltaTracker delta, CallbackInfo ci) {
        RtxRemixMod.beginWorldCaptureFrame();
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER))
    private void rtxremix$finishWorld(DeltaTracker delta, CallbackInfo ci) {
        RtxRemixMod.finishWorldCaptureFrame();
    }

    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void rtxremix$captureFirstPersonModel(ItemInHandRenderer heldItemRenderer,
            float tickDelta, PoseStack matrices, SubmitNodeCollector collector, LocalPlayer player, int light) {
        if (RtxRemixMod.shouldCaptureFirstPersonModel()) {
            RtxRemixMod.renderer.captureFirstPersonModel(heldItemRenderer, tickDelta, matrices, player, light);
        } else {
            heldItemRenderer.submitHandsWithItems(tickDelta, matrices, collector, player, light);
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(ZZFLnet/minecraft/client/renderer/SubmitNodeCollector;Z)V"))
    private void rtxremix$submitBeforeScreenEffects(DeltaTracker delta, CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /** Covers panorama and every path that omits the first-person screen effects. */
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void rtxremix$submitAtWorldTail(DeltaTracker delta, CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void rtxremix$beginGuiScope(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        rtxremix$closeGuiScope();
    }

    @Unique
    private void rtxremix$closeGuiScope() {
        ProfileScope scope = rtxremix$guiScope;
        if (scope != null) {
            rtxremix$guiScope = null;
            scope.close();
        }
    }
}
