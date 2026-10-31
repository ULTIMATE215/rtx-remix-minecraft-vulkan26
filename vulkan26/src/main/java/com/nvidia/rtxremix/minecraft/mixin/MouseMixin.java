/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixMouseAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.util.SmoothDouble;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the shared Remix overlay input policy to Minecraft's mouse callbacks. */
@Mixin(MouseHandler.class)
public abstract class MouseMixin implements RemixMouseAccess {
    @Unique private static final double rtxremix$HIDDEN_CURSOR_COORDINATE = -Double.MAX_VALUE;

    @Shadow @Final private Minecraft minecraft;
    @Shadow private MouseButtonInfo activeButton;
    @Shadow private int fakeRightMouse;
    @Shadow private boolean isLeftPressed;
    @Shadow private boolean isMiddlePressed;
    @Shadow private boolean isRightPressed;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow private boolean ignoreFirstMove;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;
    @Shadow private void onButton(long window, MouseButtonInfo button, int action) {
        throw new AssertionError();
    }

    @Unique private boolean rtxremix$releasingActiveButton;

    @Override
    public void rtxremix$releaseActiveButton() {
        rtxremix$releasingActiveButton = true;
        try {
            if (activeButton != null) {
                // The stored button has already passed vanilla's right-click emulation.
                onButton(minecraft.getWindow().handle(), activeButton, GLFW.GLFW_RELEASE);
            }
        } finally {
            activeButton = null;
            fakeRightMouse = 0;
            isLeftPressed = false;
            isMiddlePressed = false;
            isRightPressed = false;
            rtxremix$clearMovement();
            rtxremix$releasingActiveButton = false;
        }
    }

    @Unique
    private void rtxremix$clearMovement() {
        accumulatedDX = 0;
        accumulatedDY = 0;
        smoothTurnX.reset();
        smoothTurnY.reset();
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockMovementWhileMenuOpen(
            long window, double cursorX, double cursorY, CallbackInfo ci) {
        if (window == minecraft.getWindow().handle()
                && RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            xpos = cursorX;
            ypos = cursorY;
            ignoreFirstMove = false;
            rtxremix$clearMovement();
            minecraft.getFramerateLimitTracker().onInputReceived();
            ci.cancel();
        }
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockAccumulatedMovement(CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            rtxremix$clearMovement();
            ci.cancel();
        }
    }

    // Scaled getters read the fields directly in 26.2. Cover them as well as xpos/ypos
    // so GUI extraction clears hover state even when no movement event was dispatched.
    @Inject(method = {"xpos()D", "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D"},
            at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideXWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (!rtxremix$releasingActiveButton && RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = {"ypos()D", "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D"},
            at = @At("HEAD"), cancellable = true)
    private void rtxremix$hideYWhileMenuOpen(CallbackInfoReturnable<Double> cir) {
        if (!rtxremix$releasingActiveButton && RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            cir.setReturnValue(rtxremix$HIDDEN_CURSOR_COORDINATE);
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockLockWhileMenuOpen(CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockClickWhileMenuOpen(
            long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        boolean syntheticRelease = rtxremix$releasingActiveButton
                && action == GLFW.GLFW_RELEASE && button == activeButton;
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen() && !syntheticRelease) {
            minecraft.getFramerateLimitTracker().onInputReceived();
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockScrollWhileMenuOpen(
            long window, double horizontal, double vertical, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            minecraft.getFramerateLimitTracker().onInputReceived();
            ci.cancel();
        }
    }

    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
    private void rtxremix$blockFileDropWhileMenuOpen(CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }
}
