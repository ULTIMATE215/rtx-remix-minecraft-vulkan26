/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationCapture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SpriteContents.AnimationState.class)
public abstract class SpriteAnimationStateMixin implements SpriteAnimationCapture {
    @Shadow private int frame;
    @Shadow private int subFrame;
    @Shadow @Final private SpriteContents.AnimatedTexture animationInfo;
    @Shadow public abstract boolean needsToDraw();

    @Unique private TextureAtlasSprite rtxremix$sprite;
    @Unique private long rtxremix$generation;

    @Override
    public void rtxremix$bindSprite(TextureAtlasSprite sprite) {
        rtxremix$sprite = sprite;
    }

    @Override
    public void rtxremix$captureFrame() {
        long generation = AtlasAnimationForwarder.generation();
        if (generation == 0 || rtxremix$sprite == null) return;
        if (generation == rtxremix$generation && !needsToDraw()) return;

        SpriteContents.FrameInfo current = animationInfo.frames.get(frame);
        int next = animationInfo.interpolateFrames
                ? animationInfo.frames.get((frame + 1) % animationInfo.frames.size()).index()
                : current.index();
        // Vanilla encodes this integer in the vertex ID, then divides by 1000 in the shader.
        int progress = animationInfo.interpolateFrames
                ? (int) ((float) subFrame / current.time() * 1000.0f) : 0;
        if (AtlasAnimationForwarder.addFrame(rtxremix$sprite, animationInfo.frameRowSize,
                current.index(), next, progress / 1000.0f)) {
            rtxremix$generation = generation;
        }
    }
}
