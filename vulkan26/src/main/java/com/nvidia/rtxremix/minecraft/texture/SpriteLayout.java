/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.nio.ByteBuffer;

/** Content coordinates and duplicated edge texels in Minecraft's padded atlases. */
public final class SpriteLayout {
    private SpriteLayout() {}

    public static int contentX(TextureAtlasSprite sprite) {
        return sprite.getX() + sprite.padding;
    }

    public static int contentY(TextureAtlasSprite sprite) {
        return sprite.getY() + sprite.padding;
    }

    public static void pad(byte[] atlas, int atlasWidth, TextureAtlasSprite sprite, int channels) {
        pad(atlas, atlasWidth, contentX(sprite), contentY(sprite),
                sprite.contents().width(), sprite.contents().height(), channels, sprite.padding);
    }

    /** Refreshes the border after all material channels have been composed in the content rectangle. */
    public static void pad(byte[] atlas, int atlasWidth, int x, int y,
                           int width, int height, int channels, int padding) {
        if (padding == 0) return;
        int stride = atlasWidth * channels;
        for (int row = 0; row < height; row++) {
            int first = (y + row) * stride + x * channels;
            int last = first + (width - 1) * channels;
            for (int edge = 1; edge <= padding; edge++) {
                System.arraycopy(atlas, first, atlas, first - edge * channels, channels);
                System.arraycopy(atlas, last, atlas, last + edge * channels, channels);
            }
        }
        int firstRow = y * stride + (x - padding) * channels;
        int lastRow = firstRow + (height - 1) * stride;
        int rowBytes = (width + 2 * padding) * channels;
        for (int edge = 1; edge <= padding; edge++) {
            System.arraycopy(atlas, firstRow, atlas, firstRow - edge * stride, rowBytes);
            System.arraycopy(atlas, lastRow, atlas, lastRow + edge * stride, rowBytes);
        }
    }

    /** Writes a tightly packed animation patch including all four borders and corners. */
    public static void writePadded(ByteBuffer target, byte[] content,
                                   int width, int height, int channels, int padding) {
        int rowBytes = width * channels;
        for (int y = -padding; y < height + padding; y++) {
            int row = Math.clamp(y, 0, height - 1) * rowBytes;
            for (int edge = 0; edge < padding; edge++) target.put(content, row, channels);
            target.put(content, row, rowBytes);
            for (int edge = 0; edge < padding; edge++) {
                target.put(content, row + rowBytes - channels, channels);
            }
        }
    }
}
