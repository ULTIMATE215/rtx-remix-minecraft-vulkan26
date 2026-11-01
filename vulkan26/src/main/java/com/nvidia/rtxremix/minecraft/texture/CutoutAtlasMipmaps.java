/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.List;

/** Keeps Minecraft's cutout coverage and authored mipmap strategy in a static atlas. */
final class CutoutAtlasMipmaps {
    private CutoutAtlasMipmaps() {}

    /** Images belong to Minecraft and are only borrowed during the atlas build. */
    record Sprite(int x, int y, int width, int height, int padding,
                  NativeImage[] images, byte[] ao) {
        static Sprite capture(int x, int y, int width, int height, int padding,
                              NativeImage[] images, NativeImage normal) {
            byte[] ao = null;
            if (normal != null) {
                ao = new byte[width * height];
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        ao[row * width + col] = (byte) normal.getPixel(
                                col % normal.getWidth(), row % normal.getHeight());
                    }
                }
            }
            return new Sprite(x, y, width, height, padding, images, ao);
        }

        int levelCount() {
            // Atlas rectangles and gutters must keep their boundaries at every mip.
            int alignment = Integer.numberOfTrailingZeros(x | y | width | height | padding);
            return Math.min(images.length, alignment + 1);
        }
    }

    static byte[][] build(int width, int height, List<Sprite> sprites) {
        if (width <= 0 || height <= 0 || sprites.isEmpty()) {
            throw new IllegalArgumentException("A cutout atlas needs dimensions and sprites");
        }
        int count = Integer.numberOfTrailingZeros(width | height) + 1;
        for (Sprite sprite : sprites) count = Math.min(count, sprite.levelCount());
        if (count < 1) throw new IllegalArgumentException("Missing sprite mipmaps");

        byte[][] levels = new byte[count][];
        for (int level = 0; level < count; level++) {
            levels[level] = new byte[Math.multiplyExact(Math.multiplyExact(width >> level, height >> level), 4)];
        }

        for (Sprite sprite : sprites) {
            byte[] ao = sprite.ao;
            for (int level = 0; level < count; level++) {
                int w = sprite.width >> level;
                int h = sprite.height >> level;
                int x = sprite.x >> level;
                int y = sprite.y >> level;
                int atlasWidth = width >> level;
                if (level > 0 && ao != null) ao = downsampleAo(ao, w, h);
                NativeImage source = sprite.images[level];
                byte[] target = levels[level];
                for (int row = 0; row < h; row++) {
                    for (int col = 0; col < w; col++) {
                        int rgba = NativeImagePixels.abgr(source, col, row);
                        int factor = ao == null ? 255 : ao[row * w + col] & 255;
                        int offset = ((y + row) * atlasWidth + x + col) * 4;
                        target[offset] = (byte) ((rgba & 255) * factor / 255);
                        target[offset + 1] = (byte) ((rgba >>> 8 & 255) * factor / 255);
                        target[offset + 2] = (byte) ((rgba >>> 16 & 255) * factor / 255);
                        target[offset + 3] = (byte) (rgba >>> 24);
                    }
                }
                SpriteLayout.pad(target, atlasWidth, x, y, w, h, 4, sprite.padding >> level);
            }
        }
        return levels;
    }

    private static byte[] downsampleAo(byte[] source, int width, int height) {
        byte[] result = new byte[width * height];
        int stride = width * 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = y * 2 * stride + x * 2;
                int sum = (source[offset] & 255) + (source[offset + 1] & 255)
                        + (source[offset + stride] & 255) + (source[offset + stride + 1] & 255);
                result[y * width + x] = (byte) ((sum + 2) / 4);
            }
        }
        return result;
    }
}
