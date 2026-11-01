/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.input.RemixInputGate;
import com.nvidia.rtxremix.minecraft.input.RemixKeyboardAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes keyboard input through the Remix overlay gate. While a native overlay's capture
 * session is open every key and character is withheld from Minecraft. Settings have no
 * opening shortcut. Plain R toggles camera recording once an in-game
 * command has armed it, and = and - scrub time of day while recording. Injecting into
 * {@code KeyboardHandler.keyPress} receives the modifier mask captured by GLFW with the
 * key event. Matched shortcuts cancel the event so vanilla does not also
 * handle it.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardMixin implements RemixKeyboardAccess {
    @Shadow
    private long debugCrashKeyTime;

    @Shadow
    private long debugCrashKeyReportedTime;

    @Shadow
    private long debugCrashKeyReportedCount;

    @Shadow
    private boolean usedDebugKeyAsModifier;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onKey(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (window != client.getWindow().handle()) {
            return;
        }
        int key = event.key();
        int mods = event.modifiers();
        boolean altDown = action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_X
                && event.hasAltDown();
        boolean controlDown = action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_X
                && event.hasControlDown();
        RemixInputGate.KeyDecision decision = RtxRemixMod.getRemixInputGate().onKey(
                key, action, altDown, controlDown);

        if (decision == RemixInputGate.KeyDecision.BLOCK) {
            client.getFramerateLimitTracker().onInputReceived();
            ci.cancel();
            return;
        }
        if (client.gui.screen() != null
                || (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT)) {
            return;
        }

        if (action == GLFW.GLFW_PRESS
                && key == GLFW.GLFW_KEY_R
                && RtxRemixMod.shouldHandlePlainPathRecordingShortcut()) {
            ci.cancel();
            RtxRemixMod.togglePathRecording();
        } else if (RtxRemixMod.handleCameraTimeShortcut(key, action, mods)) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onChar(long window, CharacterEvent event, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Inject(method = "preeditCallback", at = @At("HEAD"), cancellable = true)
    private void rtxremix$onPreedit(long window, PreeditEvent event, CallbackInfo ci) {
        if (RtxRemixMod.getRemixInputGate().isCaptureSessionOpen()) {
            ci.cancel();
        }
    }

    @Override
    public void rtxremix$resetDebugKeyState() {
        debugCrashKeyTime = -1L;
        debugCrashKeyReportedTime = -1L;
        debugCrashKeyReportedCount = -1L;
        usedDebugKeyAsModifier = false;
    }
}
