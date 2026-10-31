/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationSource;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.SpriteAnimationCapture;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin implements AtlasAnimationSource {

    @Shadow @Final private Identifier location;
    @Shadow private List<SpriteContents.AnimationState> animatedTexturesStates;

    @Override
    public void rtxremix$captureAnimations() {
        for (int i = 0; i < animatedTexturesStates.size(); i++) {
            ((SpriteAnimationCapture) animatedTexturesStates.get(i)).rtxremix$captureFrame();
        }
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        if (BlockTextureManager.BLOCK_ATLAS_ID.equals(location)) rtxremix$captureAnimations();
    }

    @Inject(method = "clearTextureData", at = @At("HEAD"))
    private void rtxremix$discardAnimationPointers(CallbackInfo ci) {
        AtlasAnimationForwarder.onAtlasClosed((TextureAtlas) (Object) this);
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
    }
}
