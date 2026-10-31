/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/** All 26.2 glyph sources reach this packed upload, including NativeImage and Unihex. */
@Mixin(CommandEncoder.class)
public abstract class TextureUploadMixin {
    @Inject(method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("RETURN"))
    private void rtxremix$mirrorGlyphUpload(GpuTexture texture, ByteBuffer pixels,
                                          int mipLevel, int layer, int x, int y,
                                          int width, int height, CallbackInfo ci) {
        GlyphPageMirror page = GlyphPageMirror.uploadTarget(texture);
        if (page == null || mipLevel != 0 || layer != 0 || width <= 0 || height <= 0) {
            return;
        }
        int channels = texture.getFormat().componentCount();
        page.blit(x, y, width, height, channels, pixels, width * channels);
    }
}
