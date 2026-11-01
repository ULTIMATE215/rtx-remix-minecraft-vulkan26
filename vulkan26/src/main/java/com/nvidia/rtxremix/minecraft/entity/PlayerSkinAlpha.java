/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.BitSet;
import java.util.IdentityHashMap;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Authored fractional alpha for one uploaded texture. Vanilla shares a translucent render
 * layer between a player's opaque body and its outer skin. Only parts sampling fractional
 * alpha need Remix's blended path; binary coverage remains an opaque, alpha-tested hit.
 *
 * <p>The mask is a snapshot, and the per-cuboid cache belongs to that snapshot. A texture
 * upload replaces both, so a changed skin cannot inherit a stale material classification.
 */
final class PlayerSkinAlpha {
    private static final PlayerSkinAlpha BINARY = new PlayerSkinAlpha(0, 0, null);

    private final int width;
    private final int height;
    private final BitSet fractional;
    private final IdentityHashMap<ModelPart.Polygon[], Boolean> cuboids;

    private PlayerSkinAlpha(int width, int height, BitSet fractional) {
        this.width = width;
        this.height = height;
        this.fractional = fractional;
        cuboids = fractional == null ? null : new IdentityHashMap<>();
    }

    static PlayerSkinAlpha fromRgba(byte[] rgba, int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height * 4 != rgba.length) {
            throw new IllegalArgumentException("Invalid RGBA texture dimensions");
        }
        BitSet fractional = null;
        for (int pixel = 0, offset = 3; offset < rgba.length; pixel++, offset += 4) {
            int alpha = rgba[offset] & 0xFF;
            if (alpha > 0 && alpha < 255) {
                if (fractional == null) fractional = new BitSet(width * height);
                fractional.set(pixel);
            }
        }
        return fractional == null ? BINARY : new PlayerSkinAlpha(width, height, fractional);
    }

    RenderLayerUtils.SurfaceMode surfaceMode(ModelPart.Polygon[] sides, TextureAtlasSprite sprite,
                                             int tintColor) {
        // Unknown geometry may carry per-vertex alpha. A remapped sprite does not use
        // these UVs directly, and an entity tint can intentionally fade the whole body.
        if (sides == null || sprite != null || (tintColor >>> 24) != 255) {
            return RenderLayerUtils.SurfaceMode.TRANSLUCENT;
        }
        if (fractional == null) return RenderLayerUtils.SurfaceMode.CUTOUT;

        Boolean blended = cuboids.get(sides);
        if (blended == null) {
            blended = samplesFractionalAlpha(sides);
            cuboids.put(sides, blended);
        }
        return blended ? RenderLayerUtils.SurfaceMode.TRANSLUCENT : RenderLayerUtils.SurfaceMode.CUTOUT;
    }

    private boolean samplesFractionalAlpha(ModelPart.Polygon[] sides) {
        for (ModelPart.Polygon side : sides) {
            float minU = Float.POSITIVE_INFINITY, minV = Float.POSITIVE_INFINITY;
            float maxU = Float.NEGATIVE_INFINITY, maxV = Float.NEGATIVE_INFINITY;
            for (ModelPart.Vertex vertex : side.vertices()) {
                minU = Math.min(minU, vertex.u());
                minV = Math.min(minV, vertex.v());
                maxU = Math.max(maxU, vertex.u());
                maxV = Math.max(maxV, vertex.v());
            }
            // Wrapped or invalid UVs are not evidence that a custom part is opaque.
            if (!(minU >= 0 && minV >= 0 && maxU <= 1 && maxV <= 1)) return true;
            int left = (int) Math.floor(minU * width);
            int right = (int) Math.ceil(maxU * width);
            int top = (int) Math.floor(minV * height);
            int bottom = (int) Math.ceil(maxV * height);
            for (int y = top; y < bottom; y++) {
                int pixel = fractional.nextSetBit(y * width + left);
                if (pixel >= 0 && pixel < y * width + right) return true;
            }
        }
        return false;
    }
}
